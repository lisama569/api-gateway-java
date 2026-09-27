package com.github.lisama569.apigatewayjava.tests;

import com.github.lisama569.apigatewayjava.access.DefaultAccessService;
import com.github.lisama569.apigatewayjava.api.GatewayRequest;
import com.github.lisama569.apigatewayjava.api.GatewayResult;
import com.github.lisama569.apigatewayjava.api.GatewayService;
import com.github.lisama569.apigatewayjava.concurrency.InFlightConcurrencyLimiter;
import com.github.lisama569.apigatewayjava.core.error.ErrorCode;
import com.github.lisama569.apigatewayjava.identity.InMemoryCallerDirectory;
import com.github.lisama569.apigatewayjava.quota.FixedWindowQuotaService;
import com.github.lisama569.apigatewayjava.upstream.CircuitState;
import com.github.lisama569.apigatewayjava.upstream.InMemoryBackend;
import com.github.lisama569.apigatewayjava.upstream.ResilientUpstreamClient;
import com.github.lisama569.apigatewayjava.upstream.SlidingWindowCircuitBreaker;
import com.github.lisama569.apigatewayjava.upstream.UpstreamFailureKind;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 熔断恢复期并发放量测试：进入 HALF_OPEN 的那一刻只有有限数量（默认 1）的探测真正触达上游，
 * 其余并发请求快速降级为 UPSTREAM_RECOVERY_LIMITED（未触达上游、无重复副作用）；
 * 探测成功自动恢复正常放行，探测失败重新打开继续隔离。
 */
class RecoveryProbeTest {

    private static final Logger log = LoggerFactory.getLogger(RecoveryProbeTest.class);
    private static final String KEY = "orders:v1";

    private InMemoryBackend backend;
    private long[] clock;
    private SlidingWindowCircuitBreaker breaker;
    private GatewayService gateway;
    private ResilientUpstreamClient upstream;

    @BeforeEach
    void setUp() {
        backend = new InMemoryBackend();
        clock = new long[]{0L};
        // 恢复期探测名额 = 1
        breaker = new SlidingWindowCircuitBreaker(3, 5000L, 1, () -> clock[0]);
        upstream = new ResilientUpstreamClient(backend, breaker, 1, 60_000L);
        InMemoryCallerDirectory callers = new InMemoryCallerDirectory();
        callers.register("c", java.util.Set.of("*"), 100_000);
        FixedWindowQuotaService quota = new FixedWindowQuotaService(60_000L, () -> clock[0]);
        DefaultAccessService access = new DefaultAccessService(callers, quota);
        gateway = new GatewayService(new InMemoryVersionRegistry(), access, upstream,
                new InFlightConcurrencyLimiter(Integer.MAX_VALUE, false));
    }

    private GatewayResult get() {
        GatewayRequest req = new GatewayRequest("orders", "v1", "GET", "\"x\"");
        return gateway.route(req, "c", false);
    }

    private void openBreaker() {
        backend.configure(KEY, UpstreamFailureKind.ERROR_5XX, 10);
        get(); get(); get();
        assertEquals(CircuitState.OPEN, breaker.state(KEY));
    }

    @Test
    void onlyOneProbeTouchesUpstreamDuringRecoveryAndOthersAreDegraded() throws Exception {
        openBreaker();                       // 后端已触达 3 次
        backend.clearFaults();               // 上游「已恢复」
        clock[0] = 5001L;                    // 冷却结束 -> 下一次准入进入 HALF_OPEN

        // 让真正触达上游的探测请求阻塞在后端，制造「探测在途、其余并发涌入」的窗口
        backend.block(KEY, 1);
        ExecutorService pool = Executors.newFixedThreadPool(6);
        Future<GatewayResult> probeFuture = pool.submit(this::get);
        backend.awaitEntered(KEY);

        // 此刻唯一探测正在触达上游；再并发 5 个请求，全部必须快速降级、0 触达
        int callsWhenProbeInFlight = backend.callCount(KEY);
        assertEquals(4, callsWhenProbeInFlight, "恢复期应只放行了 1 个探测（3 故障 + 1 探测）");

        for (int i = 0; i < 5; i++) {
            GatewayResult r = get();
            assertFalse(r.isSuccess());
            assertEquals(ErrorCode.UPSTREAM_RECOVERY_LIMITED, r.getErrorCode(),
                    "恢复期非探测请求必须是可识别的降级结果，而非 500/排队");
            assertEquals(503, r.getHttpStatus());
            assertTrue(r.isDegraded());
        }
        assertEquals(callsWhenProbeInFlight, backend.callCount(KEY),
                "恢复期被降级的并发请求不得触达上游（无重复副作用）");
        log.info("[恢复期] 探测在途时 5 个并发请求全部 UPSTREAM_RECOVERY_LIMITED，backendCalls={}",
                backend.callCount(KEY));

        // 放行探测：探测成功 -> CLOSED，后续请求恢复正常放行
        backend.releaseBlock(KEY);
        GatewayResult probe = probeFuture.get(5, TimeUnit.SECONDS);
        assertTrue(probe.isSuccess(), "探测请求本身应成功");
        pool.shutdown();

        assertEquals(CircuitState.CLOSED, breaker.state(KEY));
        assertTrue(get().isSuccess(), "探测成功后自动恢复正常放行");
        log.info("[恢复期] 唯一探测成功，熔断器回到 CLOSED，后续请求正常触达");
    }

    @Test
    void failedProbeKeepsIsolationAndReopensWithFreshCooldown() {
        openBreaker();
        clock[0] = 5001L;                    // 进入 HALF_OPEN，发放 1 个探测名额

        // 上游仍故障：探测失败，应重新 OPEN 并重新计时
        GatewayResult probe = get();
        assertFalse(probe.isSuccess());
        assertEquals(ErrorCode.UPSTREAM_ERROR, probe.getErrorCode());
        assertEquals(CircuitState.OPEN, breaker.state(KEY), "探测失败必须继续保持隔离");

        // 冷却未到：请求仍是熔断打开降级，不触达上游
        int calls = backend.callCount(KEY);
        GatewayResult stillOpen = get();
        assertEquals(ErrorCode.UPSTREAM_UNAVAILABLE, stillOpen.getErrorCode());
        assertEquals(calls, backend.callCount(KEY), "重新打开的冷却期内不得触达上游");

        // 新一轮冷却结束后才再次发放探测名额
        backend.clearFaults();
        clock[0] = 10_002L;
        assertTrue(get().isSuccess());
        assertEquals(CircuitState.CLOSED, breaker.state(KEY));
        log.info("[恢复期] 探测失败重新隔离，冷却后再次探测成功才恢复");
    }
}
