package com.github.lisama569.apigatewayjava.tests;

import com.github.lisama569.apigatewayjava.access.DefaultAccessService;
import com.github.lisama569.apigatewayjava.api.BatchItemView;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 批量三种结果（全成功 200 / 部分成功 207 / 整批失败 502）不仅整体状态可一眼区分，
 * data(batchData) 还必须与子请求逐条对齐、逐条归因：成功项有数据，失败项有独立
 * errorCode/message/attempts/degraded。整批失败不得被当成部分成功，
 * 且整个过程不产生重试策略之外的上游调用。
 */
class BatchItemAttributionTest {

    private static final Logger log = LoggerFactory.getLogger(BatchItemAttributionTest.class);
    private static final String KEY = "orders:v1";

    private InMemoryBackend backend;
    private long[] clock;
    private SlidingWindowCircuitBreaker breaker;
    private GatewayService gateway;

    @BeforeEach
    void setUp() {
        backend = new InMemoryBackend();
        clock = new long[]{0L};
        breaker = new SlidingWindowCircuitBreaker(3, 5000L, 1, () -> clock[0]);
        ResilientUpstreamClient upstream = new ResilientUpstreamClient(backend, breaker, 2, 2_000L);
        InMemoryCallerDirectory callers = new InMemoryCallerDirectory();
        callers.register("caller-a", java.util.Set.of("*"), 10_000);
        FixedWindowQuotaService quota = new FixedWindowQuotaService(60_000L, () -> clock[0]);
        gateway = new GatewayService(new InMemoryVersionRegistry(),
                new DefaultAccessService(callers, quota), upstream);
    }

    private GatewayResult batch(String faultHeader, int subCount) {
        GatewayRequest req = new GatewayRequest("orders", "v1", "GET", String.valueOf(subCount),
                faultHeader, 0L);
        return gateway.route(req, "caller-a", true);
    }

    private BatchItemView item(GatewayResult r, int index) {
        return (BatchItemView) r.getBatchData().get(index);
    }

    @Test
    void allSuccessItemsCarryDataAndAlignedIndex() {
        GatewayResult r = batch(null, 3);
        assertTrue(r.isSuccess());
        assertEquals(200, r.getHttpStatus());
        assertNull(r.getErrorCode());
        assertEquals(3, r.getBatchData().size());
        for (int i = 0; i < 3; i++) {
            BatchItemView it = item(r, i);
            assertEquals(i, it.index(), "index 必须与子请求位置对齐");
            assertTrue(it.success());
            assertEquals(200, it.httpStatus());
            assertNotNull(it.data(), "成功项必须携带上游数据");
            assertNull(it.errorCode());
            assertFalse(it.degraded());
        }
        assertEquals(3, backend.callCount(KEY));
        log.info("[逐条归因] 全成功：3 条均携带数据，触达 3 次");
    }

    @Test
    void partialFailureItemsAreSeparatelyAttributed() {
        // 前 1 条故障（其 2 次幂等尝试均失败），其余 2 条成功 -> 207
        GatewayResult r = batch("ERROR_5XX:1", 3);
        assertFalse(r.isSuccess());
        assertEquals(ErrorCode.UPSTREAM_PARTIAL_FAILURE, r.getErrorCode());
        assertEquals(207, r.getHttpStatus());
        assertEquals(3, r.getBatchData().size(), "成功项与失败项都必须保留");

        BatchItemView failed = item(r, 0);
        assertFalse(failed.success());
        assertEquals("ERROR_5XX", failed.errorCode());
        assertEquals(502, failed.httpStatus());
        assertEquals(2, failed.attempts(), "该条按幂等策略真实尝试 2 次");
        assertFalse(failed.degraded());
        assertNotNull(failed.message(), "失败项必须有可归因原因");

        assertTrue(item(r, 1).success());
        assertTrue(item(r, 2).success());
        assertEquals(4, backend.callCount(KEY), "2(失败条重试) + 1 + 1 = 4，无额外调用");
        log.info("[逐条归因] 部分失败：第 0 条 ERROR_5XX 尝试 2 次，其余成功，触达 4 次");
    }

    @Test
    void allFailedIs502NotPartialAndEveryItemAttributed() {
        GatewayResult r = batch("ERROR_5XX", 3); // 全部故障，每条 2 次尝试
        assertFalse(r.isSuccess());
        assertEquals(ErrorCode.UPSTREAM_BATCH_FAILURE, r.getErrorCode(),
                "整批失败必须标识为 BATCH_FAILURE，不得伪装成部分成功");
        assertEquals(502, r.getHttpStatus());
        assertFalse(r.isDegraded());
        assertEquals(3, r.getBatchData().size(), "整批失败也逐条保留，不能丢明细");
        for (int i = 0; i < 3; i++) {
            BatchItemView it = item(r, i);
            assertEquals(i, it.index());
            assertFalse(it.success());
            assertEquals("ERROR_5XX", it.errorCode());
            assertEquals(2, it.attempts());
        }
        assertEquals(6, backend.callCount(KEY), "3 条 * 2 次尝试 = 6，无重复副作用");
        log.info("[逐条归因] 整批失败：3 条均 ERROR_5XX，触达 6 次（无额外副作用）");
    }

    @Test
    void timeoutItemIsAttributedAs504WithinPartialBatch() {
        // 前 1 条超时（2 次尝试都超时），其余成功 -> 207，失败条标记 UPSTREAM_TIMEOUT(504)
        GatewayResult r = batch("TIMEOUT:1", 2);
        assertEquals(ErrorCode.UPSTREAM_PARTIAL_FAILURE, r.getErrorCode());
        BatchItemView timedOut = item(r, 0);
        assertFalse(timedOut.success());
        assertEquals("TIMEOUT", timedOut.errorCode());
        assertEquals(504, timedOut.httpStatus());
        assertEquals(2, timedOut.attempts());
        assertTrue(item(r, 1).success());
        log.info("[逐条归因] 超时条目标记 TIMEOUT(504)，成功条目正常");
    }

    @Test
    void failedProbeBatchIs502WithReachedAndOpenRejectedItemsAttributed() {
        // 打开熔断：3 次 POST 连续失败
        backend.configure(KEY, UpstreamFailureKind.ERROR_5XX, 10);
        for (int i = 0; i < 3; i++) {
            gateway.route(new GatewayRequest("orders", "v1", "POST", "\"x\""), "caller-a", false);
        }
        assertEquals(CircuitState.OPEN, breaker.state(KEY));

        // 恢复期发批量（顺序执行）：第 1 条拿到唯一名额真正触达上游，但探测失败
        // （2 次幂等尝试均失败）-> 熔断器重新 OPEN；第 2、3 条随即被 OPEN 挡回，
        // 不触达上游。整批无成功项 -> 502 BATCH_FAILURE（不是部分成功），逐条归因清晰。
        clock[0] = 5001L;
        GatewayResult r = batch(null, 3);
        assertFalse(r.isSuccess());
        assertEquals(ErrorCode.UPSTREAM_BATCH_FAILURE, r.getErrorCode(),
                "1 条真实探测失败 + 2 条 OPEN 挡回、无成功项，必须是整批失败而非部分成功");
        assertEquals(502, r.getHttpStatus());

        BatchItemView probeItem = item(r, 0);
        assertFalse(probeItem.success());
        assertEquals("ERROR_5XX", probeItem.errorCode());
        assertEquals(502, probeItem.httpStatus());
        assertEquals(2, probeItem.attempts(), "拿到名额的探测条按幂等策略真实尝试 2 次");
        assertFalse(probeItem.degraded());

        for (int i = 1; i <= 2; i++) {
            BatchItemView degraded = item(r, i);
            assertFalse(degraded.success());
            assertEquals("UPSTREAM_UNAVAILABLE", degraded.errorCode());
            assertEquals(503, degraded.httpStatus());
            assertEquals(0, degraded.attempts(), "重新隔离后其余条目不得触达上游");
            assertTrue(degraded.degraded());
        }
        // 3 次打开 + 探测条 2 次尝试 = 5，被挡回的两条绝不打上游
        assertEquals(5, backend.callCount(KEY), "探测失败后继续隔离，挡回条目不触达上游");
        assertEquals(CircuitState.OPEN, breaker.state(KEY), "探测失败必须重新 OPEN 继续隔离");
        log.info("[逐条归因] 探测失败批量：1 条尝试 2 次失败 + 2 条 OPEN 挡回，触达=5，继续隔离");
    }
}
