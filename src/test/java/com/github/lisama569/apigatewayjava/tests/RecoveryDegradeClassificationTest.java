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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 两类快速降级必须在「返回结果（错误码/HTTP/degradeReason）」与「日志」上明确区分：
 * <ul>
 *   <li>熔断 OPEN 挡回：UPSTREAM_UNAVAILABLE(503) + CIRCUIT_OPEN，应继续退避；</li>
 *   <li>恢复期(HALF_OPEN)探测名额用尽：UPSTREAM_RECOVERY_LIMITED(503)
 *       + RECOVERY_PROBE_CAPACITY_FULL，可尽快重试。</li>
 * </ul>
 * 两者均不触达上游；整批降级时每条子请求也逐条保留、逐条归因。
 */
class RecoveryDegradeClassificationTest {

    private static final Logger log = LoggerFactory.getLogger(RecoveryDegradeClassificationTest.class);
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
        // maxAttempts=1：每条放行请求只触达上游一次，便于精确断言触达次数
        ResilientUpstreamClient upstream = new ResilientUpstreamClient(backend, breaker, 1, 2_000L);
        InMemoryCallerDirectory callers = new InMemoryCallerDirectory();
        callers.register("caller-a", java.util.Set.of("*"), 10_000);
        FixedWindowQuotaService quota = new FixedWindowQuotaService(60_000L, () -> clock[0]);
        gateway = new GatewayService(new InMemoryVersionRegistry(),
                new DefaultAccessService(callers, quota), upstream);
    }

    private GatewayResult getOnce() {
        return gateway.route(new GatewayRequest("orders", "v1", "GET", "\"x\""),
                "caller-a", false);
    }

    private void openCircuit() {
        backend.configure(KEY, UpstreamFailureKind.ERROR_5XX, 3);
        for (int i = 0; i < 3; i++) {
            getOnce();
        }
        assertEquals(CircuitState.OPEN, breaker.state(KEY));
    }

    @Test
    void openRejectAndRecoveryLimitedAreDistinctForSingleRequest() {
        openCircuit();

        // 1) OPEN 挡回
        GatewayResult openReject = getOnce();
        assertFalse(openReject.isSuccess());
        assertEquals(ErrorCode.UPSTREAM_UNAVAILABLE, openReject.getErrorCode());
        assertEquals(503, openReject.getHttpStatus());
        assertTrue(openReject.isDegraded());
        assertEquals(BatchItemView.REASON_CIRCUIT_OPEN, openReject.getDegradeReason());
        assertEquals(3, backend.callCount(KEY), "OPEN 挡回不得触达上游");
        log.info("[分类] OPEN 挡回 code={} reason={}",
                openReject.getErrorCode(), openReject.getDegradeReason());

        // 冷却结束进入恢复期；随后第一个请求作为唯一探测并在途（闸门挡住，模拟探测尚未结算）
        clock[0] = 5001L;
        backend.blockOnGate();
        Thread probe = new Thread(() -> {
            GatewayRequest req = new GatewayRequest("orders", "v1", "GET", "\"probe\"");
            GatewayResult r = gateway.route(req, "caller-a", false);
            log.info("[分类] 探测请求结算 success={}", r.isSuccess());
        });
        probe.start();
        try {
            backend.awaitEntered(1, 5_000L); // 探测已占用唯一名额、尚未结算

            // 2) 恢复期名额用尽：错误码与原因必须与 OPEN 挡回不同
            GatewayResult limited = getOnce();
            assertFalse(limited.isSuccess());
            assertEquals(ErrorCode.UPSTREAM_RECOVERY_LIMITED, limited.getErrorCode());
            assertEquals(503, limited.getHttpStatus());
            assertTrue(limited.isDegraded());
            assertEquals(BatchItemView.REASON_RECOVERY_PROBE_CAPACITY_FULL,
                    limited.getDegradeReason());
            assertNotSameReason(openReject, limited);
            // 名额用尽的快速返回绝不能多打上游：仍只有 1 个探测真正触达
            assertEquals(4, backend.callCount(KEY),
                    "恢复期只有拿到名额的探测触达上游，名额用尽的请求不得触达");
            log.info("[分类] 恢复期名额用尽 code={} reason={} 触达={}",
                    limited.getErrorCode(), limited.getDegradeReason(), backend.callCount(KEY));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        } finally {
            backend.releaseGate();
            try {
                probe.join(5_000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        // 探测成功 -> CLOSED，自动恢复正常放行，全程无重复触达
        assertEquals(CircuitState.CLOSED, breaker.state(KEY));
        assertTrue(getOnce().isSuccess());
        assertEquals(5, backend.callCount(KEY));
        log.info("[分类] 探测成功后 CLOSED，触达={}，无重复副作用", backend.callCount(KEY));
    }

    @Test
    void failedProbeReopensAndRejectsAreClassifiedAsOpen() {
        openCircuit();
        // 探测请求直接失败（无并发）：HALF_OPEN 探测失败 -> 重新 OPEN
        backend.configure(KEY, UpstreamFailureKind.ERROR_5XX, 10);
        clock[0] = 5001L;
        GatewayResult failedProbe = getOnce();
        assertFalse(failedProbe.isSuccess());
        assertEquals(ErrorCode.UPSTREAM_ERROR, failedProbe.getErrorCode());
        assertEquals(CircuitState.OPEN, breaker.state(KEY));

        // 下一冷却周期之前的挡回仍是 OPEN 分类（不是恢复期名额用尽）
        GatewayResult openReject = getOnce();
        assertEquals(ErrorCode.UPSTREAM_UNAVAILABLE, openReject.getErrorCode());
        assertEquals(BatchItemView.REASON_CIRCUIT_OPEN, openReject.getDegradeReason());
        assertEquals(4, backend.callCount(KEY), "继续隔离期间不得触达上游");
        log.info("[分类] 探测失败后继续隔离 OPEN，触达保持 {}", backend.callCount(KEY));
    }

    @Test
    void batchDegradedKeepsPerItemAttributionWithDistinctReasons() {
        openCircuit();

        // OPEN 期整批：503 UPSTREAM_UNAVAILABLE，每条子请求逐条归因且不触达上游
        GatewayResult openBatch = gateway.route(
                new GatewayRequest("orders", "v1", "GET", "3"), "caller-a", true);
        assertEquals(ErrorCode.UPSTREAM_UNAVAILABLE, openBatch.getErrorCode());
        assertTrue(openBatch.isDegraded());
        assertEquals(BatchItemView.REASON_CIRCUIT_OPEN, openBatch.getDegradeReason());
        assertEquals(3, openBatch.getBatchData().size(), "整批降级也必须逐条保留，不得丢明细");
        for (Object o : openBatch.getBatchData()) {
            BatchItemView item = (BatchItemView) o;
            assertFalse(item.success());
            assertEquals(503, item.httpStatus());
            assertEquals("UPSTREAM_UNAVAILABLE", item.errorCode());
            assertTrue(item.degraded());
            assertEquals(0, item.attempts(), "降级条目未触达上游");
        }
        assertEquals(3, backend.callCount(KEY));
        log.info("[批量分类] OPEN 整批降级，3 条逐条 CIRCUIT_OPEN 归因，触达不变");

        // 恢复期：先推进时钟，再让唯一探测在途占用名额；随后整批每条都是 RECOVERY_LIMITED
        clock[0] = 5001L;
        backend.blockOnGate();
        Thread probe = new Thread(() ->
                gateway.route(new GatewayRequest("orders", "v1", "GET", "\"probe\""),
                        "caller-a", false));
        probe.start();
        try {
            backend.awaitEntered(1, 5_000L);
            GatewayResult limitedBatch = gateway.route(
                    new GatewayRequest("orders", "v1", "GET", "3"), "caller-a", true);
            assertEquals(ErrorCode.UPSTREAM_RECOVERY_LIMITED, limitedBatch.getErrorCode());
            assertEquals(BatchItemView.REASON_RECOVERY_PROBE_CAPACITY_FULL,
                    limitedBatch.getDegradeReason());
            assertEquals(3, limitedBatch.getBatchData().size());
            for (int i = 0; i < 3; i++) {
                BatchItemView item = (BatchItemView) limitedBatch.getBatchData().get(i);
                assertEquals(i, item.index(), "条目必须与子请求位置对齐");
                assertEquals("RECOVERY_LIMITED", item.errorCode());
                assertEquals(503, item.httpStatus());
                assertTrue(item.degraded());
            }
            // 仍只有一个探测触达（前 3 次打开 + 1 个探测 = 4）
            assertEquals(4, backend.callCount(KEY),
                    "整批恢复期降级不得触达上游，只有在途探测占用名额");
            log.info("[批量分类] 恢复期整批降级，3 条逐条 RECOVERY_LIMITED 归因，触达=4");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        } finally {
            backend.releaseGate();
            try {
                probe.join(5_000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void assertNotSameReason(GatewayResult a, GatewayResult b) {
        assertTrue(a.getErrorCode() != b.getErrorCode(),
                "OPEN 挡回与恢复期名额用尽必须错误码不同");
        assertTrue(!a.getDegradeReason().equals(b.getDegradeReason()),
                "两类降级的 degradeReason 必须不同");
    }
}
