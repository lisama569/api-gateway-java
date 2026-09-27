package com.github.lisama569.apigatewayjava.tests;

import com.github.lisama569.apigatewayjava.access.DefaultAccessService;
import com.github.lisama569.apigatewayjava.api.BatchItemView;
import com.github.lisama569.apigatewayjava.api.BatchStatus;
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
import com.github.lisama569.apigatewayjava.upstream.UpstreamFailureKind;
import com.github.lisama569.apigatewayjava.version.InMemoryVersionRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 批量三种整体结果状态必须可区分：ALL_SUCCESS / PARTIAL_SUCCESS / ALL_FAILED；
 * 成功项与失败项逐条保留、可归因；整批失败不得伪装成部分成功，也不得产生重复上游调用。
 */
class BatchResultStatusTest {

    private static final Logger log = LoggerFactory.getLogger(BatchResultStatusTest.class);
    private static final String KEY = "orders:v1";

    private InMemoryBackend backend;
    private long[] clock;
    private SlidingWindowCircuitBreaker breaker;
    private GatewayService gateway;

    @BeforeEach
    void setUp() {
        backend = new InMemoryBackend();
        clock = new long[]{0L};
        breaker = new SlidingWindowCircuitBreaker(3, 5000L, () -> clock[0]);
        ResilientUpstreamClient upstream = new ResilientUpstreamClient(backend, breaker, 1, 1_000L);
        InMemoryCallerDirectory callers = new InMemoryCallerDirectory();
        callers.register("c", java.util.Set.of("*"), 100_000);
        FixedWindowQuotaService quota = new FixedWindowQuotaService(60_000L, () -> clock[0]);
        DefaultAccessService access = new DefaultAccessService(callers, quota);
        gateway = new GatewayService(new InMemoryVersionRegistry(), access, upstream,
                new InFlightConcurrencyLimiter(Integer.MAX_VALUE, false));
    }

    private GatewayResult batch(String method, String subCount) {
        GatewayRequest req = new GatewayRequest("orders", "v1", method, subCount);
        return gateway.route(req, "c", true);
    }

    @Test
    void allSuccessState() {
        GatewayResult r = batch("GET", "3");
        assertTrue(r.isSuccess());
        assertEquals(BatchStatus.ALL_SUCCESS, r.getBatchStatus());
        assertEquals(200, r.getHttpStatus());
        assertNull(r.getErrorCode());
        assertEquals(3, r.getBatchSucceeded());
        assertEquals(0, r.getBatchFailed());
        assertEquals(3, r.getBatchItems().size());
        assertTrue(r.getBatchItems().stream().allMatch(BatchItemView::success));
        log.info("[批量三态] ALL_SUCCESS：3/3 成功");
    }

    @Test
    void partialSuccessStateKeepsEveryItemAttributable() {
        backend.configure(KEY, UpstreamFailureKind.ERROR_5XX, 1);
        GatewayResult r = batch("GET", "3");
        assertFalse(r.isSuccess());
        assertEquals(BatchStatus.PARTIAL_SUCCESS, r.getBatchStatus());
        assertEquals(ErrorCode.UPSTREAM_PARTIAL_FAILURE, r.getErrorCode());
        assertEquals(207, r.getHttpStatus());
        assertEquals(2, r.getBatchSucceeded());
        assertEquals(1, r.getBatchFailed());

        long ok = r.getBatchItems().stream().filter(BatchItemView::success).count();
        long bad = r.getBatchItems().stream().filter(i -> !i.success()).count();
        assertEquals(2, ok);
        assertEquals(1, bad);
        BatchItemView failedItem = r.getBatchItems().stream()
                .filter(i -> !i.success()).findFirst().orElseThrow();
        assertEquals("UPSTREAM_ERROR", failedItem.errorCode());
        assertEquals(502, failedItem.status());
        log.info("[批量三态] PARTIAL_SUCCESS：2 成功 1 失败，失败项 errorCode={} 可归因",
                failedItem.errorCode());
    }

    @Test
    void allFailedStateIsDistinctFromPartialAndNoDuplicateSideEffects() {
        // POST 非幂等：每个子请求只尝试一次，故障 3 条 -> 整批失败且后端恰好被触达 3 次
        backend.configure(KEY, UpstreamFailureKind.ERROR_5XX, 3);
        GatewayResult r = batch("POST", "3");
        assertFalse(r.isSuccess());
        assertEquals(BatchStatus.ALL_FAILED, r.getBatchStatus(), "整批失败不得伪装成部分成功");
        assertEquals(ErrorCode.UPSTREAM_BATCH_ALL_FAILED, r.getErrorCode());
        assertEquals(502, r.getHttpStatus());
        assertEquals(0, r.getBatchSucceeded());
        assertEquals(3, r.getBatchFailed());
        assertTrue(r.getBatchItems().stream().noneMatch(BatchItemView::success));
        assertEquals(3, backend.callCount(KEY), "非幂等整批失败不得重试，避免重复业务副作用");
        log.info("[批量三态] ALL_FAILED：0 成功 3 失败，后端触达=3（无重试、无重复副作用）");
    }

    @Test
    void allFailedByBreakerOpenDoesNotTouchUpstream() {
        // 连续 3 次 POST 失败打开熔断器
        backend.configure(KEY, UpstreamFailureKind.ERROR_5XX, 10);
        batch("POST", "3"); // 这一批就把熔断打开（3 次连续失败）
        int callsBefore = backend.callCount(KEY);

        // 熔断打开后再来一批：全部子请求被快速降级，整批失败，且 0 次触达上游
        GatewayResult r = batch("POST", "3");
        assertFalse(r.isSuccess());
        assertEquals(BatchStatus.ALL_FAILED, r.getBatchStatus());
        assertEquals(ErrorCode.UPSTREAM_BATCH_ALL_FAILED, r.getErrorCode());
        assertTrue(r.isDegraded());
        assertTrue(r.getBatchItems().stream().allMatch(BatchItemView::degraded),
                "整批均为降级项（未触达上游）");
        assertEquals(callsBefore, backend.callCount(KEY), "熔断打开的整批不得产生任何上游调用");
        assertEquals("UPSTREAM_UNAVAILABLE",
                r.getBatchItems().get(0).errorCode(), "失败项可归因为 UPSTREAM_UNAVAILABLE");
        log.info("[批量三态] ALL_FAILED(熔断)：3 条全部降级，后端触达保持 {} 不变", callsBefore);
    }
}
