package com.github.lisama569.apigatewayjava.tests;

import com.github.lisama569.apigatewayjava.access.DefaultAccessService;
import com.github.lisama569.apigatewayjava.api.BatchStatus;
import com.github.lisama569.apigatewayjava.api.GatewayRequest;
import com.github.lisama569.apigatewayjava.api.GatewayResult;
import com.github.lisama569.apigatewayjava.api.GatewayService;
import com.github.lisama569.apigatewayjava.core.error.ErrorCode;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 批量调用三种结果状态可区分：全部成功(200) / 部分成功(207) / 整批失败(502)，
 * 以及熔断打开时的整体降级(503)。成功项与失败项逐条保留、可归因。
 */
class BatchResultStatusTest {

    private static final Logger log = LoggerFactory.getLogger(BatchResultStatusTest.class);
    private static final String KEY = "orders:v1";

    private InMemoryBackend backend;
    private GatewayService gateway;

    @BeforeEach
    void setUp() {
        backend = new InMemoryBackend();
        SlidingWindowCircuitBreaker breaker = new SlidingWindowCircuitBreaker(3, 5000L, () -> 0L);
        ResilientUpstreamClient upstream = new ResilientUpstreamClient(backend, breaker, 2, 200L);
        InMemoryCallerDirectory callers = new InMemoryCallerDirectory();
        callers.register("caller-a", java.util.Set.of("*"), 10_000);
        FixedWindowQuotaService quota = new FixedWindowQuotaService(60_000L, () -> 0L);
        gateway = new GatewayService(new InMemoryVersionRegistry(),
                new DefaultAccessService(callers, quota), upstream);
    }

    private GatewayResult batch(int subCount) {
        GatewayRequest req = new GatewayRequest("orders", "v1", "GET", String.valueOf(subCount));
        GatewayResult r = gateway.route(req, "caller-a", true);
        log.info("[批量结果] subs={} -> success={} code={} http={} items={} backendCalls={}",
                subCount, r.isSuccess(), r.getErrorCode(), r.getHttpStatus(),
                r.getBatchData() == null ? 0 : r.getBatchData().size(), backend.callCount(KEY));
        return r;
    }

    @Test
    void allSuccessIs200() {
        GatewayResult r = batch(3);
        assertTrue(r.isSuccess());
        assertEquals(200, r.getHttpStatus());
        assertEquals(BatchStatus.ALL_SUCCEEDED, r.getBatchStatus(),
                "全成功必须有明确标识，无需逐条比对");
        assertEquals(3, r.getBatchData().size());
        assertEquals(3, backend.callCount(KEY));
    }

    @Test
    void partialFailureIs207AndKeepsEveryItem() {
        // 2 次故障：子请求 1 两次尝试均失败，其余 2 条成功
        backend.configure(KEY, UpstreamFailureKind.ERROR_5XX, 2);
        GatewayResult r = batch(3);
        assertFalse(r.isSuccess());
        assertEquals(ErrorCode.UPSTREAM_PARTIAL_FAILURE, r.getErrorCode());
        assertEquals(207, r.getHttpStatus());
        assertEquals(BatchStatus.PARTIAL_FAILURE, r.getBatchStatus(),
                "部分成功必须有明确标识");
        assertEquals(3, r.getBatchData().size(), "成功项与失败项必须逐条保留、可归因");
    }

    @Test
    void allFailedIs502AndDistinctFromPartial() {
        // 故障覆盖全部子请求的全部尝试：3 条 * 2 次尝试 = 6 次触达均失败
        backend.configure(KEY, UpstreamFailureKind.ERROR_5XX, 10);
        GatewayResult r = batch(3);
        assertFalse(r.isSuccess());
        assertEquals(ErrorCode.UPSTREAM_BATCH_FAILURE, r.getErrorCode(),
                "整批失败必须可识别，不得伪装成部分成功");
        assertEquals(502, r.getHttpStatus());
        assertNotEquals(ErrorCode.UPSTREAM_PARTIAL_FAILURE, r.getErrorCode());
        assertEquals(BatchStatus.ALL_FAILED, r.getBatchStatus(),
                "整批失败必须有明确标识，不得被当成部分成功");
        assertNotEquals(BatchStatus.PARTIAL_FAILURE, r.getBatchStatus());
        assertEquals(3, r.getBatchData().size(), "失败项仍逐条保留、可归因");
        assertFalse(r.isDegraded());
        // 不产生超出既有重试策略的重复上游调用：3 条 * 最多 2 次尝试 = 6
        assertEquals(6, backend.callCount(KEY),
                "整批失败不得产生重试策略之外的重复上游调用/副作用");
        log.info("[批量整批失败] code={} http={} 触达次数={}（=3条*2次尝试，无额外副作用）",
                r.getErrorCode(), r.getHttpStatus(), backend.callCount(KEY));
    }

    @Test
    void circuitOpenBatchIsDegraded503WithoutTouchingUpstream() {
        // 先制造连续失败打开熔断器（POST 不重试，每次恰好 1 次触达）
        backend.configure(KEY, UpstreamFailureKind.ERROR_5XX, 3);
        for (int i = 0; i < 3; i++) {
            gateway.route(new GatewayRequest("orders", "v1", "POST", "\"x\""), "caller-a", false);
        }
        assertEquals(3, backend.callCount(KEY));

        GatewayResult r = batch(3);
        assertFalse(r.isSuccess());
        assertEquals(ErrorCode.UPSTREAM_UNAVAILABLE, r.getErrorCode());
        assertEquals(503, r.getHttpStatus());
        assertTrue(r.isDegraded());
        assertEquals(BatchStatus.DEGRADED, r.getBatchStatus(),
                "整批快速降级也必须有明确标识");
        assertEquals(3, r.getBatchData().size(),
                "整批降级后每条子请求的结果仍必须逐条保留、可逐条归因");
        r.getBatchData().forEach(item -> assertTrue(
                item.toString().contains("CIRCUIT_OPEN"),
                "每条降级子结果都要能归因到熔断隔离： " + item));
        assertEquals(3, backend.callCount(KEY), "熔断期间整批快速降级，不得触达上游");
        log.info("[批量降级] 熔断打开，整批 503 且未触达上游 backendCalls={}", backend.callCount(KEY));
    }
}
