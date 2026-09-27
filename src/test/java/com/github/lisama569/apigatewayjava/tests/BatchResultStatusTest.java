package com.github.lisama569.apigatewayjava.tests;

import com.github.lisama569.apigatewayjava.access.DefaultAccessService;
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
        assertEquals(3, r.getBatchData().size(), "整批降级也必须逐条保留结果，不得丢明细");
        for (int i = 0; i < 3; i++) {
            com.github.lisama569.apigatewayjava.api.BatchItemView item =
                    (com.github.lisama569.apigatewayjava.api.BatchItemView)
                            r.getBatchData().get(i);
            assertEquals(i, item.index(), "降级条目仍按位置对齐");
            assertFalse(item.success());
            assertEquals("UPSTREAM_UNAVAILABLE", item.errorCode(), "每条独立归因");
            assertEquals(503, item.httpStatus());
            assertTrue(item.degraded());
            assertEquals(0, item.attempts(), "降级条目未触达上游");
        }
        assertEquals(3, backend.callCount(KEY), "熔断期间整批快速降级，不得触达上游");
        log.info("[批量降级] 熔断打开，整批 503 且未触达上游 backendCalls={}，3 条逐条归因",
                backend.callCount(KEY));
    }
}
