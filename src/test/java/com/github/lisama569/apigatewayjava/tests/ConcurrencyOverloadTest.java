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
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 在途并发上限测试：
 * 超过上限快速返回 CALLER_OVERLOADED（与 QUOTA_EXCEEDED 可区分、不消耗配额、不排队）；
 * 请求结束名额立即释放，压力回落后自动恢复；按「调用方+资源」维度独立计量。
 */
class ConcurrencyOverloadTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrencyOverloadTest.class);
    private static final String KEY = "orders:v1";

    private InMemoryBackend backend;
    private long[] clock;
    private GatewayService gateway;

    @BeforeEach
    void setUp() {
        backend = new InMemoryBackend();
        clock = new long[]{0L};
        SlidingWindowCircuitBreaker breaker = new SlidingWindowCircuitBreaker(3, 5000L, () -> clock[0]);
        ResilientUpstreamClient upstream = new ResilientUpstreamClient(backend, breaker, 1, 60_000L);
        InMemoryCallerDirectory callers = new InMemoryCallerDirectory();
        callers.register("heavy", java.util.Set.of("*"), 100_000);
        FixedWindowQuotaService quota = new FixedWindowQuotaService(60_000L, () -> clock[0]);
        DefaultAccessService access = new DefaultAccessService(callers, quota);
        // 在途上限 2，按调用方维度
        gateway = new GatewayService(new InMemoryVersionRegistry(), access, upstream,
                new InFlightConcurrencyLimiter(2, false));
    }

    private GatewayResult route(String method) {
        GatewayRequest req = new GatewayRequest("orders", "v1", method, "\"x\"");
        return gateway.route(req, "heavy", false);
    }

    @Test
    void overloadedIsDistinctFromQuotaExhaustedAndReleasesSlots() throws Exception {
        backend.block(KEY, 2);
        ExecutorService pool = Executors.newFixedThreadPool(4);

        // 先占满 2 个在途名额（请求会阻塞在后端）
        Future<?> f1 = pool.submit(() -> route("GET"));
        Future<?> f2 = pool.submit(() -> route("GET"));
        backend.awaitEntered(KEY);

        // 第 3、4 个请求必须快速过载，且不触达上游
        int backendCallsAtOverflow = backend.callCount(KEY);
        GatewayResult over1 = route("GET");
        GatewayResult over2 = route("GET");
        assertFalse(over1.isSuccess());
        assertFalse(over2.isSuccess());
        assertEquals(ErrorCode.CALLER_OVERLOADED, over1.getErrorCode());
        assertEquals(429, over1.getHttpStatus());
        assertNotQuotaExceeded(over1);
        assertEquals(backendCallsAtOverflow, backend.callCount(KEY),
                "过载拒绝不应触达上游（在途名额已占满）");
        log.info("[在途限流] 第 3/4 个请求快速过载 code=CALLER_OVERLOADED，与 QUOTA_EXCEEDED 可区分");

        // 放行前两个在途请求，名额应立即释放，压力回落后自动恢复
        backend.releaseBlock(KEY);
        f1.get(5, TimeUnit.SECONDS);
        f2.get(5, TimeUnit.SECONDS);
        pool.shutdown();

        GatewayResult recovered = route("GET");
        assertTrue(recovered.isSuccess(), "在途名额释放后应自动恢复放行");
        log.info("[在途限流] 在途请求结束，名额释放，后续请求恢复正常");
    }

    private void assertNotQuotaExceeded(GatewayResult r) {
        // errorCode 本身即区分；这里再断言不是 QUOTA_EXCEEDED
        assertFalse(ErrorCode.QUOTA_EXCEEDED == r.getErrorCode());
    }

    @Test
    void inFlightPermitReleasedEvenWhenUpstreamFails() {
        // 熔断阈值设得足够高，避免本用例中途打开熔断；这里只验证失败也释放名额
        SlidingWindowCircuitBreaker highThreshold =
                new SlidingWindowCircuitBreaker(100, 5000L, () -> clock[0]);
        ResilientUpstreamClient client =
                new ResilientUpstreamClient(backend, highThreshold, 1, 60_000L);
        InMemoryCallerDirectory callers = new InMemoryCallerDirectory();
        callers.register("heavy", java.util.Set.of("*"), 100_000);
        FixedWindowQuotaService quota = new FixedWindowQuotaService(60_000L, () -> clock[0]);
        DefaultAccessService access = new DefaultAccessService(callers, quota);
        GatewayService gw = new GatewayService(new InMemoryVersionRegistry(), access, client,
                new InFlightConcurrencyLimiter(2, false));

        backend.configure(KEY, com.github.lisama569.apigatewayjava.upstream.UpstreamFailureKind.ERROR_5XX, 5);
        // 上限 2：若失败不释放名额，连续 3 个请求就会出现 CALLER_OVERLOADED
        for (int i = 0; i < 5; i++) {
            GatewayRequest req = new GatewayRequest("orders", "v1", "POST", "\"x\"");
            GatewayResult r = gw.route(req, "heavy", false);
            assertFalse(r.isSuccess());
            assertEquals(ErrorCode.UPSTREAM_ERROR, r.getErrorCode(),
                    "上游失败后名额必须释放，第 " + (i + 1) + " 个请求不应被判为过载");
        }
        log.info("[在途限流] 5 次连续上游失败均未占用名额（每次结束即释放）");
    }

    @Test
    void concurrencyIsCountedPerCallerAndResourceWhenScoped() throws Exception {
        // 单独构造一个 caller_resource 维度限流器（上限 1）
        SlidingWindowCircuitBreaker breaker = new SlidingWindowCircuitBreaker(3, 5000L, () -> clock[0]);
        ResilientUpstreamClient upstream = new ResilientUpstreamClient(backend, breaker, 1, 60_000L);
        InMemoryCallerDirectory callers = new InMemoryCallerDirectory();
        callers.register("u", java.util.Set.of("*"), 100_000);
        FixedWindowQuotaService quota = new FixedWindowQuotaService(60_000L, () -> clock[0]);
        DefaultAccessService access = new DefaultAccessService(callers, quota);
        GatewayService scoped = new GatewayService(new InMemoryVersionRegistry(), access, upstream,
                new InFlightConcurrencyLimiter(1, true));

        backend.block("users:v1", 1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        CountDownLatch finished = new CountDownLatch(1);
        AtomicInteger otherResourceResult = new AtomicInteger(-1);

        // orders:v1 先不占名额；占用 users:v1 的唯一在途名额
        Future<?> f = pool.submit(() -> {
            GatewayRequest req = new GatewayRequest("users", "v1", "GET", null);
            GatewayResult r = scoped.route(req, "u", false);
            otherResourceResult.set(r.getHttpStatus());
            finished.countDown();
        });
        backend.awaitEntered("users:v1");

        // 不同资源 orders:v1 有独立名额，应被放行（不受 users:v1 在途占用影响）
        GatewayRequest orders = new GatewayRequest("orders", "v1", "GET", null);
        GatewayResult ok = scoped.route(orders, "u", false);
        assertTrue(ok.isSuccess(), "caller_resource 维度下不同资源各自计量，应被放行");

        // 同资源再来一个 -> 过载
        GatewayRequest usersAgain = new GatewayRequest("users", "v1", "GET", null);
        GatewayResult over = scoped.route(usersAgain, "u", false);
        assertEquals(ErrorCode.CALLER_OVERLOADED, over.getErrorCode());

        backend.releaseBlock("users:v1");
        assertTrue(finished.await(5, TimeUnit.SECONDS));
        pool.shutdown();
        assertEquals(200, otherResourceResult.get());
        log.info("[在途限流] scope=caller_resource：users 占满不影响 orders，同资源第二个请求过载");
    }
}
