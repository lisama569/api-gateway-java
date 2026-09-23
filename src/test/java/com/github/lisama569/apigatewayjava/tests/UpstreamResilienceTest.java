package com.github.lisama569.apigatewayjava.tests;

import com.github.lisama569.apigatewayjava.core.error.ErrorCode;
import com.github.lisama569.apigatewayjava.api.GatewayRequest;
import com.github.lisama569.apigatewayjava.api.GatewayResult;
import com.github.lisama569.apigatewayjava.api.GatewayService;
import com.github.lisama569.apigatewayjava.access.DefaultAccessService;
import com.github.lisama569.apigatewayjava.identity.InMemoryCallerDirectory;
import com.github.lisama569.apigatewayjava.quota.FixedWindowQuotaService;
import com.github.lisama569.apigatewayjava.upstream.InMemoryBackend;
import com.github.lisama569.apigatewayjava.upstream.ResilientUpstreamClient;
import com.github.lisama569.apigatewayjava.upstream.SlidingWindowCircuitBreaker;
import com.github.lisama569.apigatewayjava.upstream.UpstreamFailureKind;
import com.github.lisama569.apigatewayjava.version.InMemoryVersionRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 上游故障隔离与降级测试：重试安全、超时归一化、熔断打开/探测恢复、批量部分失败。
 * 全部使用内存后端，不依赖外部服务。
 */
class UpstreamResilienceTest {

    private static final Logger log = LoggerFactory.getLogger(UpstreamResilienceTest.class);

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
        breaker = new SlidingWindowCircuitBreaker(3, 5000L, () -> clock[0]);
        upstream = new ResilientUpstreamClient(backend, breaker, 2, 200L);
        InMemoryCallerDirectory callers = new InMemoryCallerDirectory();
        callers.register("caller-a", java.util.Set.of("*"), 10_000);
        FixedWindowQuotaService quota = new FixedWindowQuotaService(60_000L, () -> clock[0]);
        DefaultAccessService access = new DefaultAccessService(callers, quota);
        gateway = new GatewayService(new InMemoryVersionRegistry(), access, upstream);
    }

    private GatewayResult route(String method) {
        GatewayRequest req = new GatewayRequest("orders", "v1", method, "\"x\"");
        GatewayResult r = gateway.route(req, "caller-a", false);
        log.info("[请求输入] orders v1 {} -> success={} code={} backendCalls={}",
                method, r.isSuccess(), r.getErrorCode(), backend.callCount(KEY));
        return r;
    }

    @Test
    void idempotentRequestIsRetriedAndSucceeds() {
        backend.configure(KEY, UpstreamFailureKind.ERROR_5XX, 1);
        GatewayResult r = route("GET");
        assertTrue(r.isSuccess());
        assertEquals(2, backend.callCount(KEY), "幂等请求失败一次后应重试，共触达 2 次");
    }

    @Test
    void nonIdempotentRequestIsNeverRetried() {
        backend.configure(KEY, UpstreamFailureKind.ERROR_5XX, 5);
        GatewayResult r = route("POST");
        assertFalse(r.isSuccess());
        assertEquals(ErrorCode.UPSTREAM_ERROR, r.getErrorCode());
        assertEquals(1, backend.callCount(KEY), "写请求不得重试，避免重复业务副作用");
    }

    @Test
    void timeoutIsNormalizedToUpstreamTimeout() {
        backend.setLatencyMillis(KEY, 500L);
        GatewayResult r = route("GET");
        assertFalse(r.isSuccess());
        assertEquals(ErrorCode.UPSTREAM_TIMEOUT, r.getErrorCode());
        assertEquals(504, r.getHttpStatus());
        assertEquals(2, backend.callCount(KEY), "幂等请求超时后允许一次重试");
    }

    @Test
    void circuitOpensAfterConsecutiveFailuresAndRecoversAutomatically() {
        backend.configure(KEY, UpstreamFailureKind.ERROR_5XX, 3);
        // 3 次连续失败（POST 不重试，每次恰好一次触达）
        route("POST");
        route("POST");
        route("POST");
        assertEquals(3, backend.callCount(KEY));

        // 熔断打开：快速失败/降级，不触达上游
        GatewayResult fastFail = route("POST");
        assertFalse(fastFail.isSuccess());
        assertEquals(ErrorCode.UPSTREAM_UNAVAILABLE, fastFail.getErrorCode());
        assertEquals(503, fastFail.getHttpStatus());
        assertTrue(fastFail.isDegraded());
        assertEquals(3, backend.callCount(KEY), "熔断期间不得触达上游");
        log.info("[熔断] 打开后快速降级，backendCalls 保持 {}", backend.callCount(KEY));

        // 时间推进超过 OPEN 窗口 -> HALF_OPEN 探测；故障已恢复 -> 回到 CLOSED
        backend.clearFaults();
        clock[0] = 5001L;
        GatewayResult probe = route("GET");
        assertTrue(probe.isSuccess(), "探测成功后应自动恢复正常路径");
        assertEquals(4, backend.callCount(KEY), "半开期只放行一个探测请求");

        GatewayResult again = route("GET");
        assertTrue(again.isSuccess());
        assertEquals(5, backend.callCount(KEY));
        log.info("[恢复] 探测成功，熔断器回到正常路径");
    }

    @Test
    void batchAllSuccessReturnsEveryItem() {
        GatewayRequest req = new GatewayRequest("orders", "v1", "GET", "3");
        GatewayResult r = gateway.route(req, "caller-a", true);
        assertTrue(r.isSuccess());
        assertEquals(3, r.getBatchData().size());
        log.info("[批量成功] 3 条子请求全部成功并逐条返回");
    }

    @Test
    void batchPartialFailureIsIsolatedAndDistinguishable() {
        // 故障需覆盖子请求 1 的全部（两次）尝试，使其重试后仍失败；其余子请求成功
        backend.configure(KEY, UpstreamFailureKind.ERROR_5XX, 2);
        GatewayRequest req = new GatewayRequest("orders", "v1", "GET", "3");
        GatewayResult r = gateway.route(req, "caller-a", true);
        assertFalse(r.isSuccess());
        assertEquals(ErrorCode.UPSTREAM_PARTIAL_FAILURE, r.getErrorCode());
        assertEquals(207, r.getHttpStatus());
        assertEquals(3, r.getBatchData().size(), "成功项结果必须保留，失败项归一化占位");
        log.info("[部分失败] 3 条子请求 2 成功 1 失败，结果逐条可区分");
    }
}
