package com.github.lisama569.apigatewayjava.tests;

import com.github.lisama569.apigatewayjava.access.DefaultAccessService;
import com.github.lisama569.apigatewayjava.api.GatewayRequest;
import com.github.lisama569.apigatewayjava.api.GatewayResult;
import com.github.lisama569.apigatewayjava.api.GatewayService;
import com.github.lisama569.apigatewayjava.concurrency.InFlightConcurrencyLimiter;
import com.github.lisama569.apigatewayjava.core.error.ErrorCode;
import com.github.lisama569.apigatewayjava.identity.InMemoryCallerDirectory;
import com.github.lisama569.apigatewayjava.quota.FixedWindowQuotaService;
import com.github.lisama569.apigatewayjava.upstream.InMemoryBackend;
import com.github.lisama569.apigatewayjava.upstream.ResilientUpstreamClient;
import com.github.lisama569.apigatewayjava.upstream.SlidingWindowCircuitBreaker;
import com.github.lisama569.apigatewayjava.version.InMemoryVersionRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 批量调用按子请求条数计费的测试：
 * 单请求成本不变；批量整笔计费、额度不足整笔拒绝且账目一致；
 * 并发下被放行的子请求总量不突破配额、每个放行子请求都被计入（不漏计）。
 */
class BatchQuotaTest {

    private static final Logger log = LoggerFactory.getLogger(BatchQuotaTest.class);
    private static final String KEY = "orders:v1";

    private InMemoryBackend backend;
    private long[] clock;
    private FixedWindowQuotaService quota;
    private GatewayService gateway;

    @BeforeEach
    void setUp() {
        backend = new InMemoryBackend();
        clock = new long[]{0L};
        quota = new FixedWindowQuotaService(60_000L, () -> clock[0]);
        InMemoryCallerDirectory callers = new InMemoryCallerDirectory();
        callers.register("batch-caller", java.util.Set.of("*"), 10);
        DefaultAccessService access = new DefaultAccessService(callers, quota);
        SlidingWindowCircuitBreaker breaker = new SlidingWindowCircuitBreaker(3, 5000L, () -> clock[0]);
        ResilientUpstreamClient upstream = new ResilientUpstreamClient(backend, breaker, 1, 1_000L);
        gateway = new GatewayService(new InMemoryVersionRegistry(), access, upstream,
                new InFlightConcurrencyLimiter(Integer.MAX_VALUE, false));
    }

    private GatewayResult batch(String subCount) {
        GatewayRequest req = new GatewayRequest("orders", "v1", "GET", subCount);
        return gateway.route(req, "batch-caller", true);
    }

    @Test
    void singleRequestStillCostsOne() {
        GatewayRequest req = new GatewayRequest("orders", "v1", "GET", "\"x\"");
        GatewayResult r = gateway.route(req, "batch-caller", false);
        assertTrue(r.isSuccess());
        assertEquals(1, backend.callCount(KEY), "单请求成本必须保持为 1（只触达一次）");
        log.info("[批量计费] 单请求成本=1，后端触达=1");
    }

    @Test
    void batchCostsSubRequestCountAndLogsAccounting() {
        GatewayResult r = batch("4");
        assertTrue(r.isSuccess());
        assertEquals(4, backend.callCount(KEY), "批量 4 个子请求应消耗 4 个配额、触达 4 次");
        assertEquals(4, r.getBatchTotal());
        assertEquals(4, r.getBatchSucceeded());
        log.info("[批量计费] 批量 4：本次消耗=4 已用=4/10 剩余=6（见判定日志）");

        // 再消耗 6 个：窗口恰好打满
        GatewayResult r2 = batch("6");
        assertTrue(r2.isSuccess());
        assertEquals(10, backend.callCount(KEY));

        // 剩余为 0，再来 1 个都必须整笔拒绝，且不得触达上游
        int before = backend.callCount(KEY);
        GatewayResult r3 = batch("1");
        assertFalse(r3.isSuccess());
        assertEquals(ErrorCode.QUOTA_EXCEEDED, r3.getErrorCode());
        assertEquals(429, r3.getHttpStatus());
        assertEquals(before, backend.callCount(KEY), "额度不足的整批拒绝不得触发任何上游调用");
        log.info("[批量计费] 额度不足整批拒绝，后端触达保持 {} 不变", before);
    }

    @Test
    void insufficientQuotaRejectsWholeBatchWithConsistentAccounting() {
        // 先消耗 8（两批 4），窗口剩 2
        assertTrue(batch("4").isSuccess());
        assertTrue(batch("4").isSuccess());
        assertEquals(8, backend.callCount(KEY));

        // 需要 3，只剩 2：整笔拒绝，账目不得只扣一部分（仍为 8），也不得放行
        int before = backend.callCount(KEY);
        GatewayResult r = batch("3");
        assertFalse(r.isSuccess());
        assertEquals(ErrorCode.QUOTA_EXCEEDED, r.getErrorCode());
        assertTrue(r.getMessage().contains("整批拒绝"), "必须给出可理解的整批拒绝判定：" + r.getMessage());
        assertTrue(r.getMessage().contains("剩余 2"), "判定结果必须可复现：" + r.getMessage());
        assertEquals(before, backend.callCount(KEY), "拒绝时不得扣减任何一部分额度");
        assertEquals(8, backend.callCount(KEY), "账目保持 8/10，不出现扣了一部分却放行整批");

        // 一个成本为 2 的批量恰好可以放行，证明账目没有被错误预扣
        GatewayResult fit = batch("2");
        assertTrue(fit.isSuccess());
        assertEquals(10, backend.callCount(KEY));
        log.info("[批量计费] 整批拒绝未预扣额度，随后成本=2 的批量精确放行");
    }

    @Test
    void concurrentBatchesNeverBreachOrLeakSubRequestQuota() throws InterruptedException {
        // 每批 3 个子请求，共 40 个并发批次（理论需求 120 > 配额 10）
        int threads = 40;
        int cost = 3;
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger grantedBatches = new AtomicInteger();
        AtomicInteger rejectedBatches = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    GatewayResult r = batch(String.valueOf(cost));
                    if (r.isSuccess()) {
                        grantedBatches.incrementAndGet();
                    } else {
                        rejectedBatches.incrementAndGet();
                        assertEquals(ErrorCode.QUOTA_EXCEEDED, r.getErrorCode());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(15, TimeUnit.SECONDS));
        pool.shutdown();

        // 配额 10：每批成本 3，只能整笔放行 3 批（9 个子请求），第 4 批放不下
        assertEquals(3, grantedBatches.get(), "整笔计费下放行批次数必须与额度匹配");
        assertEquals(threads - 3, rejectedBatches.get());
        // 最强保证：真实触达上游的子请求总数 == 已放行子请求总数 == 9，绝不突破也不漏计
        assertEquals(9, backend.callCount(KEY),
                "被放行并真实执行的子请求总量必须恰好为 9（不突破、不漏计）");
        log.info("[并发批量计费] threads={} grantedBatches={} rejectedBatches={} 放行子请求={}/10 backendCalls={}",
                threads, grantedBatches.get(), rejectedBatches.get(),
                grantedBatches.get() * cost, backend.callCount(KEY));
    }
}
