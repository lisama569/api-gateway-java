package com.github.lisama569.apigatewayjava.tests;

import com.github.lisama569.apigatewayjava.api.BatchItemView;
import com.github.lisama569.apigatewayjava.api.GatewayRequest;
import com.github.lisama569.apigatewayjava.api.GatewayResult;
import com.github.lisama569.apigatewayjava.api.GatewayService;
import com.github.lisama569.apigatewayjava.core.error.ErrorCode;
import com.github.lisama569.apigatewayjava.core.time.GatewayClock;
import com.github.lisama569.apigatewayjava.identity.InMemoryCallerDirectory;
import com.github.lisama569.apigatewayjava.upstream.CircuitState;
import com.github.lisama569.apigatewayjava.upstream.CircuitBreaker;
import com.github.lisama569.apigatewayjava.upstream.InMemoryBackend;
import com.github.lisama569.apigatewayjava.upstream.UpstreamFailureKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验收点：不覆盖任何 application.properties 阈值（默认在途上限=8、熔断阈值=3、
 * 冷却=5000ms、恢复期探测=1），直接跑就能稳定复现两类现象：
 * <ol>
 *   <li>在途并发过载被快速拒绝（CONCURRENCY_LIMIT_EXCEEDED，429）；</li>
 *   <li>恢复期只放量 1 个探测，其余并发快速降级，且两类降级可区分。</li>
 * </ol>
 * 并发用阻塞闸门确定性同步，冷却用可控时钟推进，全程不依赖固定 sleep 掐时间。
 */
@SpringBootTest
class DefaultsReproducibleTest {

    private static final Logger log = LoggerFactory.getLogger(DefaultsReproducibleTest.class);
    private static final String KEY = "users:v1"; // 与其他测试类的 key 隔离

    /** 可控时钟：以固定基准 + 增量推进，替代真实等待冷却。 */
    static class ControlledClock implements GatewayClock {
        final AtomicLong millis = new AtomicLong(1_000_000L);

        @Override
        public long nowMillis() {
            return millis.get();
        }

        void advance(long delta) {
            millis.addAndGet(delta);
        }
    }

    @TestConfiguration
    static class ClockConfig {
        @Bean
        @Primary
        GatewayClock controlledClock() {
            return new ControlledClock();
        }
    }

    @Autowired private GatewayService gateway;
    @Autowired private InMemoryBackend backend;
    @Autowired private CircuitBreaker circuitBreaker;
    @Autowired private ControlledClock clock;
    @Autowired private InMemoryCallerDirectory callers;

    @BeforeEach
    void reset() {
        backend.resetAll();
        circuitBreaker.reset(KEY);
        // 注册高配额专用调用方，避免本类多个测试方法累计消耗撞上目录默认配额，
        // 保证现象只能由「在途上限/熔断器」触发而不是配额
        callers.register("caller-defaults", java.util.Set.of("*"), 1_000_000);
    }

    private GatewayResult call(String caller) {
        return gateway.route(new GatewayRequest("users", "v1", "GET", "\"x\""), caller, false);
    }

    @Test
    void overloadIsReproducibleWithDefaultThresholds() throws InterruptedException {
        // 默认在途上限=8：16 并发，借助阻塞闸门让 8 个请求「持续」同时在途，
        // 其余必须被快速拒绝。进入即阻塞的 ticket 机制保证：在我们显式放行前，
        // 在途名额不会因个别请求快速返回而被腾让，因此 8 个成功/8 个拒绝确定可复现。
        backend.blockOnGate();
        int threads = 16;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<GatewayResult> results = Collections.synchronizedList(new ArrayList<>());
        // 高配额身份：配额充足，触发的只能是默认在途上限=8 而非配额
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    results.add(call("caller-defaults"));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        // 等 8 个请求真实进入上游（在途占满，且全部继续被闸门挡住）
        backend.awaitEntered(8, 10_000L);
        // 等其余 8 个线程在「8 个探针仍在途」期间全部拿到过载快速拒绝（快照计数，避免并发改写）
        long deadline = System.currentTimeMillis() + 10_000L;
        long overloadedBeforeRelease;
        while (true) {
            overloadedBeforeRelease = results.stream()
                    .filter(r -> r.getErrorCode() == ErrorCode.CONCURRENCY_LIMIT_EXCEEDED).count();
            if (overloadedBeforeRelease == 8) {
                break;
            }
            assertTrue(System.currentTimeMillis() < deadline,
                    "在 8 个探针仍在途时，其余 8 个请求必须全部被过载快速拒绝，实际 "
                            + overloadedBeforeRelease);
            Thread.sleep(1L);
        }
        assertTrue(backend.enteredCount() == 8, "放行了之前任何探针都不得完成，在途必须恒为 8");
        // 逐张放行 8 个探针，它们成功返回
        for (int i = 0; i < 8; i++) {
            backend.releaseOneGate();
        }
        assertTrue(done.await(15, TimeUnit.SECONDS));
        pool.shutdown();

        List<GatewayResult> snapshot = new ArrayList<>(results);
        long ok = snapshot.stream().filter(GatewayResult::isSuccess).count();
        long overloaded = snapshot.stream()
                .filter(r -> r.getErrorCode() == ErrorCode.CONCURRENCY_LIMIT_EXCEEDED).count();
        log.info("[默认配置-过载] 总={} 放行成功={} 过载快速拒绝={} 真实触达={}",
                threads, ok, overloaded, backend.callCount(KEY));

        assertEquals(8, ok, "默认上限=8，同时在途/成功的请求不得超过 8");
        assertEquals(8, overloaded, "其余 8 个必须被过载快速拒绝");
        assertEquals(8, backend.callCount(KEY), "被过载拒绝的请求不得触达上游");
        snapshot.stream().filter(r -> !r.isSuccess()).forEach(r -> {
            assertEquals(429, r.getHttpStatus());
            assertEquals(ErrorCode.CONCURRENCY_LIMIT_EXCEEDED, r.getErrorCode());
        });
    }

    @Test
    void halfOpenLimitedProbeIsReproducibleWithDefaultThresholds() throws InterruptedException {
        // 默认连续失败阈值=3：3 个 POST（非幂等，各触达 1 次）打开熔断
        backend.configure(KEY, UpstreamFailureKind.ERROR_5XX, 3);
        for (int i = 0; i < 3; i++) {
            GatewayResult failed = gateway.route(
                    new GatewayRequest("users", "v1", "POST", "\"w\""), "caller-defaults", false);
            assertFalse(failed.isSuccess());
        }
        assertEquals(CircuitState.OPEN, circuitBreaker.state(KEY));
        assertEquals(3, backend.callCount(KEY));

        // OPEN 挡回：UPSTREAM_UNAVAILABLE，不触达上游
        GatewayResult openReject = call("caller-defaults");
        assertEquals(ErrorCode.UPSTREAM_UNAVAILABLE, openReject.getErrorCode());
        assertEquals(BatchItemView.REASON_CIRCUIT_OPEN, openReject.getDegradeReason());
        assertEquals(3, backend.callCount(KEY));

        // 默认冷却 5000ms：用可控时钟推进，替代真实等待
        clock.advance(5_001L);

        // 默认恢复期探测=1：占住唯一名额（探测在途），其余并发必须快速降级
        // 上游已恢复：清除故障，探测将成功并自动回到 CLOSED
        backend.clearFaults();
        backend.blockOnGate();
        int threads = 9;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<GatewayResult> results = Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    results.add(call("caller-defaults"));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        backend.awaitEntered(1, 10_000L); // 恰好 1 个探测进入上游（持续被闸门挡住）
        // 等其余 8 个线程都拿到 RECOVERY_LIMITED 快速返回（探针仍在途、断路器仍 HALF_OPEN），
        // 再放行探针——杜绝「探针先成功->CLOSED->其余请求被放进去」的竞态。
        long deadline = System.currentTimeMillis() + 15_000L;
        long limited;
        while (true) {
            limited = results.stream()
                    .filter(r -> r.getErrorCode() == ErrorCode.UPSTREAM_RECOVERY_LIMITED).count();
            if (limited == 8) {
                break;
            }
            assertTrue(System.currentTimeMillis() < deadline,
                    "其余并发请求未在预期内快速降级，实际 " + limited);
            Thread.sleep(1L);
        }
        assertTrue(backend.enteredCount() == 1, "探针放行前不得有第二个请求触达上游");
        backend.releaseGate();
        assertTrue(done.await(15, TimeUnit.SECONDS));
        pool.shutdown();

        List<GatewayResult> snapshot = new ArrayList<>(results);
        long probes = snapshot.stream().filter(GatewayResult::isSuccess).count();
        long recoveryLimited = snapshot.stream()
                .filter(r -> r.getErrorCode() == ErrorCode.UPSTREAM_RECOVERY_LIMITED).count();
        long openRejects = snapshot.stream()
                .filter(r -> r.getErrorCode() == ErrorCode.UPSTREAM_UNAVAILABLE).count();
        log.info("[默认配置-恢复期] 总={} 探测成功={} 恢复期名额用尽={} OPEN挡回={} 真实触达={}",
                threads, probes, recoveryLimited, openRejects, backend.callCount(KEY));

        assertEquals(1, probes, "默认恢复期只放量 1 个探测真正触达上游");
        assertEquals(8, recoveryLimited, "其余并发必须是恢复期名额用尽的快速降级");
        assertEquals(0, openRejects, "恢复期降级不得被分类成 OPEN 挡回");
        snapshot.stream().filter(r -> !r.isSuccess()).forEach(r -> {
            assertEquals(503, r.getHttpStatus());
            assertTrue(r.isDegraded());
            assertEquals(BatchItemView.REASON_RECOVERY_PROBE_CAPACITY_FULL,
                    r.getDegradeReason(), "必须可与 OPEN 挡回区分");
        });
        // 3 次打开 + 1 个探测 = 4，绝不多打上游
        assertEquals(4, backend.callCount(KEY), "恢复期只有拿到名额的探测触达上游");
        assertEquals(CircuitState.CLOSED, circuitBreaker.state(KEY), "探测成功自动恢复");
    }

    @Test
    void demoFaultHeaderReproducesBatchThreeStatesWithoutConfigChange() {
        // 同一默认配置下，仅凭 X-Demo-Fault 头即可复现批量三态并逐条归因
        // 全成功
        GatewayResult ok = gateway.route(
                new GatewayRequest("users", "v1", "GET", "3", null, 0L), "caller-defaults", true);
        assertTrue(ok.isSuccess());
        assertEquals(200, ok.getHttpStatus());
        assertEquals(3, ok.getBatchData().size());

        // 部分成功：前 1 条故障
        GatewayResult partial = gateway.route(
                new GatewayRequest("users", "v1", "GET", "3", "ERROR_5XX:1", 0L), "caller-defaults", true);
        assertEquals(ErrorCode.UPSTREAM_PARTIAL_FAILURE, partial.getErrorCode());
        assertEquals(207, partial.getHttpStatus());
        BatchItemView failed0 = (BatchItemView) partial.getBatchData().get(0);
        assertFalse(failed0.success());
        assertEquals("ERROR_5XX", failed0.errorCode());
        for (int i = 1; i < 3; i++) {
            assertTrue(((BatchItemView) partial.getBatchData().get(i)).success());
        }

        // 整批失败
        GatewayResult allFail = gateway.route(
                new GatewayRequest("users", "v1", "GET", "3", "ERROR_5XX", 0L), "caller-defaults", true);
        assertEquals(ErrorCode.UPSTREAM_BATCH_FAILURE, allFail.getErrorCode());
        assertEquals(502, allFail.getHttpStatus());
        assertEquals(3, allFail.getBatchData().size(), "整批失败逐条保留");
        log.info("[默认配置-批量三态] 200 / 207 / 502 均可不改配置复现，且逐条可归因");
    }
}
