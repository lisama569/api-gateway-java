package com.github.lisama569.apigatewayjava.tests;

import com.github.lisama569.apigatewayjava.access.DefaultAccessService;
import com.github.lisama569.apigatewayjava.access.InFlightLimiter;
import com.github.lisama569.apigatewayjava.api.GatewayRequest;
import com.github.lisama569.apigatewayjava.api.GatewayResult;
import com.github.lisama569.apigatewayjava.api.GatewayService;
import com.github.lisama569.apigatewayjava.core.error.ErrorCode;
import com.github.lisama569.apigatewayjava.identity.InMemoryCallerDirectory;
import com.github.lisama569.apigatewayjava.quota.FixedWindowQuotaService;
import com.github.lisama569.apigatewayjava.upstream.InMemoryBackend;
import com.github.lisama569.apigatewayjava.upstream.ResilientUpstreamClient;
import com.github.lisama569.apigatewayjava.upstream.SlidingWindowCircuitBreaker;
import com.github.lisama569.apigatewayjava.version.InMemoryVersionRegistry;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 在途并发上限测试：超过上限快速失败（与配额耗尽可区分），
 * 请求结束名额立即释放，压力回落后自动恢复放行。
 */
class ConcurrencyLimitTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrencyLimitTest.class);

    @Test
    void limiterAcquireAndRelease() {
        InFlightLimiter limiter = new InFlightLimiter(2);
        assertTrue(limiter.tryAcquire("k"));
        assertTrue(limiter.tryAcquire("k"));
        assertFalse(limiter.tryAcquire("k"), "达到在途上限后必须快速失败而非排队");
        limiter.release("k");
        assertTrue(limiter.tryAcquire("k"), "释放名额后应立即恢复放行");
        assertEquals(2, limiter.current("k"));
    }

    @Test
    void overloadIsFastFailedDistinctFromQuotaAndRecovers() throws InterruptedException {
        String KEY = "orders:v1";
        InMemoryBackend backend = new InMemoryBackend();
        backend.setLatencyMillis(KEY, 300L); // 让请求在途一段时间，制造并发压力
        SlidingWindowCircuitBreaker breaker = new SlidingWindowCircuitBreaker(3, 5000L, () -> 0L);
        ResilientUpstreamClient upstream = new ResilientUpstreamClient(backend, breaker, 1, 2_000L);
        InMemoryCallerDirectory callers = new InMemoryCallerDirectory();
        callers.register("caller-a", java.util.Set.of("*"), 10_000);
        FixedWindowQuotaService quota = new FixedWindowQuotaService(60_000L, () -> 0L);
        InFlightLimiter limiter = new InFlightLimiter(2);
        GatewayService gateway = new GatewayService(new InMemoryVersionRegistry(),
                new DefaultAccessService(callers, quota), upstream, limiter);

        int threads = 6;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<GatewayResult> results = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    GatewayResult r = gateway.route(
                            new GatewayRequest("orders", "v1", "GET", "\"x\""), "caller-a", false);
                    results.add(r);
                    log.info("[过载判定] success={} code={} http={}",
                            r.isSuccess(), r.getErrorCode(), r.getHttpStatus());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(15, TimeUnit.SECONDS), "并发请求应在超时前完成（不得无限排队）");
        pool.shutdown();

        long ok = results.stream().filter(GatewayResult::isSuccess).count();
        long overloaded = results.stream()
                .filter(r -> r.getErrorCode() == ErrorCode.CONCURRENCY_LIMIT_EXCEEDED).count();
        assertEquals(threads, ok + overloaded, "每个请求要么放行要么快速过载拒绝，不得排队堆积");
        assertTrue(ok <= 2, "同时在途的请求不得超过上限 2，实际放行 " + ok);
        assertTrue(overloaded >= 1, "并发超限时必须产生可识别的过载结果");
        results.stream().filter(r -> !r.isSuccess()).forEach(r -> {
            assertEquals(ErrorCode.CONCURRENCY_LIMIT_EXCEEDED, r.getErrorCode());
            assertNotEquals(ErrorCode.QUOTA_EXCEEDED, r.getErrorCode(),
                    "过载结果必须与配额耗尽严格区分");
            assertEquals(429, r.getHttpStatus());
        });

        // 请求结束后名额立即释放：在途归零，压力回落后自动恢复放行
        assertEquals(0, limiter.current("caller-a:orders"), "所有名额必须已释放");
        backend.setLatencyMillis(KEY, 0L);
        GatewayResult after = gateway.route(
                new GatewayRequest("orders", "v1", "GET", "\"x\""), "caller-a", false);
        assertTrue(after.isSuccess(), "压力回落后无需干预即自动恢复放行");
        log.info("[过载恢复] 在途={} 压力回落后请求正常放行", limiter.current("caller-a:orders"));
    }
}
