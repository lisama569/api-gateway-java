package com.github.lisama569.apigatewayjava.tests;

import com.github.lisama569.apigatewayjava.upstream.CircuitState;
import com.github.lisama569.apigatewayjava.upstream.InMemoryBackend;
import com.github.lisama569.apigatewayjava.upstream.ResilientUpstreamClient;
import com.github.lisama569.apigatewayjava.upstream.SlidingWindowCircuitBreaker;
import com.github.lisama569.apigatewayjava.upstream.UpstreamFailureKind;
import com.github.lisama569.apigatewayjava.upstream.UpstreamOutcome;
import com.github.lisama569.apigatewayjava.upstream.UpstreamRequest;
import org.junit.jupiter.api.BeforeEach;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 故障恢复期并发规则：上游刚从连续故障中恢复时，只有有限个探测请求
 * 真正触达上游，其余并发请求快速返回降级结果；探测成功自动恢复放行，
 * 探测失败继续保持隔离。全程不产生重复副作用。
 */
class HalfOpenRecoveryTest {

    private static final Logger log = LoggerFactory.getLogger(HalfOpenRecoveryTest.class);
    private static final String KEY = "orders:v1";
    private static final int MAX_PROBES = 2;

    private InMemoryBackend backend;
    private long[] clock;
    private SlidingWindowCircuitBreaker breaker;
    private ResilientUpstreamClient upstream;

    @BeforeEach
    void setUp() {
        backend = new InMemoryBackend();
        clock = new long[]{0L};
        breaker = new SlidingWindowCircuitBreaker(3, 5000L, MAX_PROBES, () -> clock[0]);
        // maxAttempts=1：每次放行只触达上游一次，便于精确断言触达次数
        upstream = new ResilientUpstreamClient(backend, breaker, 1, 2_000L);
    }

    private UpstreamOutcome callOnce() {
        return upstream.call(new UpstreamRequest(KEY, "GET", "orders", "v1", "\"x\"", true));
    }

    /** 制造连续失败使熔断器进入 OPEN。 */
    private void openCircuit() {
        backend.configure(KEY, UpstreamFailureKind.ERROR_5XX, 3);
        for (int i = 0; i < 3; i++) {
            callOnce();
        }
        assertEquals(CircuitState.OPEN, breaker.state(KEY));
        assertEquals(3, backend.callCount(KEY));
    }

    private List<UpstreamOutcome> fireConcurrent(int threads) throws InterruptedException {
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<UpstreamOutcome> outcomes = Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    outcomes.add(callOnce());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(15, TimeUnit.SECONDS), "并发请求不得无限排队");
        pool.shutdown();
        return outcomes;
    }

    @Test
    void onlyLimitedProbesReachUpstreamDuringRecovery() throws InterruptedException {
        openCircuit();

        // 上游已恢复（清除故障），但请求需要一段时间在途，制造并发窗口
        backend.clearFaults();
        backend.setLatencyMillis(KEY, 300L);
        clock[0] = 5001L; // 超过 open-duration，进入 HALF_OPEN

        List<UpstreamOutcome> outcomes = fireConcurrent(10);
        long succeeded = outcomes.stream().filter(UpstreamOutcome::success).count();
        long degraded = outcomes.stream().filter(UpstreamOutcome::degraded).count();
        log.info("[恢复期并发] 成功(探测)={} 降级(快速失败)={} 触达上游={}",
                succeeded, degraded, backend.callCount(KEY));

        assertEquals(MAX_PROBES, succeeded, "恢复期只放行有限个探测请求");
        assertEquals(8, degraded, "其余并发请求必须快速返回降级结果而非一拥而上");
        // 3 次打开熔断 + 2 个探测 = 5，绝无额外触达（无重复副作用）
        assertEquals(5, backend.callCount(KEY));

        // 探测成功 -> 自动恢复 CLOSED，后续请求正常放行
        assertEquals(CircuitState.CLOSED, breaker.state(KEY));
        backend.setLatencyMillis(KEY, 0L);
        assertTrue(callOnce().success(), "探测成功后应自动恢复正常放行");
        log.info("[恢复] 熔断器 CLOSED，正常路径恢复");
    }

    @Test
    void failedProbeKeepsIsolation() throws InterruptedException {
        openCircuit();

        // 上游仍未恢复：故障持续，探测将失败
        backend.configure(KEY, UpstreamFailureKind.ERROR_5XX, 10);
        backend.setLatencyMillis(KEY, 300L);
        clock[0] = 5001L;

        List<UpstreamOutcome> outcomes = fireConcurrent(10);
        long succeeded = outcomes.stream().filter(UpstreamOutcome::success).count();
        long degraded = outcomes.stream().filter(UpstreamOutcome::degraded).count();
        log.info("[探测失败] 成功={} 降级={} 触达上游={}", succeeded, degraded, backend.callCount(KEY));

        assertEquals(0, succeeded);
        assertEquals(8, degraded, "非探测请求快速降级，不触达上游");
        assertEquals(5, backend.callCount(KEY), "只有 2 个探测触达上游且均失败");
        // 探测失败 -> 重新 OPEN，继续保持隔离
        assertEquals(CircuitState.OPEN, breaker.state(KEY));
        UpstreamOutcome rejected = callOnce();
        assertTrue(rejected.degraded(), "隔离期内请求继续快速降级");
        assertEquals(5, backend.callCount(KEY), "隔离期内不得触达上游");
        log.info("[隔离保持] 探测失败后熔断器重新 OPEN，继续隔离");
    }
}
