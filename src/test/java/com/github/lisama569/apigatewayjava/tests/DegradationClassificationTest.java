package com.github.lisama569.apigatewayjava.tests;

import com.github.lisama569.apigatewayjava.access.DefaultAccessService;
import com.github.lisama569.apigatewayjava.api.GatewayRequest;
import com.github.lisama569.apigatewayjava.api.GatewayResult;
import com.github.lisama569.apigatewayjava.api.GatewayService;
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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 两类快速降级在网关返回结果上必须明确可区分：
 * 熔断隔离期拒绝 UPSTREAM_UNAVAILABLE（503）
 * vs 恢复期探测名额耗尽 UPSTREAM_RECOVERY_LIMITED（503，上游正在恢复）。
 * 全程确定性：使用注入时钟推进冷却窗口，用条件等待探测在途，不靠固定 sleep 断言。
 */
class DegradationClassificationTest {

    private static final Logger log = LoggerFactory.getLogger(DegradationClassificationTest.class);
    private static final String KEY = "users:v1";

    private InMemoryBackend backend;
    private long[] clock;
    private SlidingWindowCircuitBreaker breaker;
    private GatewayService gateway;

    @BeforeEach
    void setUp() {
        backend = new InMemoryBackend();
        clock = new long[]{0L};
        breaker = new SlidingWindowCircuitBreaker(3, 5000L, 1, () -> clock[0]);
        ResilientUpstreamClient upstream = new ResilientUpstreamClient(backend, breaker, 1, 2_000L);
        InMemoryCallerDirectory callers = new InMemoryCallerDirectory();
        callers.register("caller-a", java.util.Set.of("*"), 10_000);
        FixedWindowQuotaService quota = new FixedWindowQuotaService(60_000L, () -> clock[0]);
        gateway = new GatewayService(new InMemoryVersionRegistry(),
                new DefaultAccessService(callers, quota), upstream);
    }

    private GatewayResult route(String method) {
        return gateway.route(new GatewayRequest("users", "v1", method, "\"x\""), "caller-a", false);
    }

    /** 等待探测请求真正进入上游（触达次数增加），条件等待而非掐时间。 */
    private void awaitCallCount(int expected) {
        long deadline = System.currentTimeMillis() + 5_000;
        while (backend.callCount(KEY) < expected && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(5); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        assertEquals(expected, backend.callCount(KEY), "探测请求应在超时前真正触达上游");
    }

    @Test
    void isolationRejectionAndRecoverySlotRejectionAreDistinguishable() throws Exception {
        // 3 次连续失败打开熔断器（POST 不重试）
        backend.configure(KEY, UpstreamFailureKind.ERROR_5XX, 3);
        for (int i = 0; i < 3; i++) {
            route("POST");
        }
        assertEquals(CircuitState.OPEN, breaker.state(KEY));

        // 隔离期拒绝：UPSTREAM_UNAVAILABLE
        GatewayResult isolated = route("GET");
        assertFalse(isolated.isSuccess());
        assertEquals(ErrorCode.UPSTREAM_UNAVAILABLE, isolated.getErrorCode());
        assertEquals(503, isolated.getHttpStatus());
        assertTrue(isolated.isDegraded());
        assertEquals(3, backend.callCount(KEY), "隔离期拒绝不触达上游");
        log.info("[隔离期拒绝] code={} http={} degraded={}",
                isolated.getErrorCode(), isolated.getHttpStatus(), isolated.isDegraded());

        // 进入恢复期：上游已恢复，但探测请求带延迟，制造并发窗口
        backend.clearFaults();
        backend.setLatencyMillis(KEY, 400L);
        clock[0] = 5001L;

        CompletableFuture<GatewayResult> probe = CompletableFuture.supplyAsync(() -> route("GET"));
        awaitCallCount(4); // 唯一的探测名额已被占用

        // 探测名额耗尽的拒绝：UPSTREAM_RECOVERY_LIMITED，与隔离期拒绝严格区分
        GatewayResult limited = route("GET");
        assertFalse(limited.isSuccess());
        assertEquals(ErrorCode.UPSTREAM_RECOVERY_LIMITED, limited.getErrorCode());
        assertNotEquals(ErrorCode.UPSTREAM_UNAVAILABLE, limited.getErrorCode(),
                "恢复期名额耗尽必须与熔断隔离期拒绝可区分");
        assertEquals(503, limited.getHttpStatus());
        assertTrue(limited.isDegraded());
        assertEquals(4, backend.callCount(KEY), "名额耗尽的快速降级不触达上游");
        log.info("[恢复期名额耗尽] code={} http={} degraded={}（与 UPSTREAM_UNAVAILABLE 区分）",
                limited.getErrorCode(), limited.getHttpStatus(), limited.isDegraded());

        // 探测成功后自动恢复正常放行
        GatewayResult probeResult = probe.get(5, TimeUnit.SECONDS);
        assertTrue(probeResult.isSuccess());
        assertEquals(CircuitState.CLOSED, breaker.state(KEY));
        backend.setLatencyMillis(KEY, 0L);
        assertTrue(route("GET").isSuccess(), "探测成功后应自动恢复正常放行");
        log.info("[恢复完成] 探测成功后 CLOSED，正常路径恢复，全过程触达={}",
                backend.callCount(KEY));
    }
}
