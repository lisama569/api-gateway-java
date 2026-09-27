package com.github.lisama569.apigatewayjava.tests;

import com.github.lisama569.apigatewayjava.access.InFlightLimiter;
import com.github.lisama569.apigatewayjava.api.GatewayRequest;
import com.github.lisama569.apigatewayjava.api.GatewayResult;
import com.github.lisama569.apigatewayjava.api.GatewayService;
import com.github.lisama569.apigatewayjava.core.error.ErrorCode;
import com.github.lisama569.apigatewayjava.identity.InMemoryCallerDirectory;
import com.github.lisama569.apigatewayjava.upstream.CircuitBreaker;
import com.github.lisama569.apigatewayjava.upstream.CircuitState;
import com.github.lisama569.apigatewayjava.upstream.InMemoryBackend;
import com.github.lisama569.apigatewayjava.upstream.UpstreamFailureKind;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 默认配置（不覆盖任何 application.properties）即可在本地稳定复现：
 * 1) 在途并发过载快速拒绝 CONCURRENCY_LIMIT_EXCEEDED；
 * 2) 恢复期只有有限探测触达上游，其余请求拿到与隔离期拒绝可区分的
 *    UPSTREAM_RECOVERY_LIMITED；探测成功自动恢复。
 *
 * 全部断言基于条件轮询（等待状态/计数到达预期），不依赖固定 sleep 掐时间，
 * 不依赖外部服务。
 */
@SpringBootTest
class DefaultConfigReproducibilityTest {

    private static final Logger log = LoggerFactory.getLogger(DefaultConfigReproducibilityTest.class);
    private static final String CALLER = "caller-loadtest";
    private static final String OVERLOAD_KEY = "orders:v2";
    private static final String RECOVERY_KEY = "users:v1";

    @Autowired private GatewayService gateway;
    @Autowired private InMemoryBackend backend;
    @Autowired private InFlightLimiter limiter;
    @Autowired private CircuitBreaker breaker;
    @Autowired private InMemoryCallerDirectory callers;
    @Autowired private Environment env;

    @BeforeEach
    void setUp() {
        // 独立调用方 + 超大配额，避免与其他 @SpringBootTest 用例互相干扰；
        // 其余组件一律使用默认配置
        callers.register(CALLER, java.util.Set.of("*"), 1_000_000);
    }

    @AfterEach
    void tearDown() {
        // 清理注入的故障与延迟，熔断器恢复 CLOSED，保证共享上下文下其他测试不受影响
        backend.clearFaults();
        backend.setLatencyMillis(OVERLOAD_KEY, 0L);
        backend.setLatencyMillis(RECOVERY_KEY, 0L);
        breaker.reset(OVERLOAD_KEY);
        breaker.reset(RECOVERY_KEY);
    }

    private void awaitCondition(String desc, BooleanSupplier condition, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(10); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        assertTrue(condition.getAsBoolean(), desc + "（等待超时，默认配置下应稳定复现）");
    }

    @Test
    void defaultsAreLocallyReproducible() {
        // 默认阈值本身就适合本地复现：上限足够小、恢复期探测数有限
        assertEquals(4, limiter.maxInFlight(),
                "默认在途并发上限应为小值（4），不改配置即可复现过载");
        assertEquals("4", env.getProperty("gateway.concurrency.max-in-flight"));
        assertEquals("1", env.getProperty("gateway.circuit.half-open-max-probes"));
        assertEquals("3", env.getProperty("gateway.circuit.failure-threshold"));
        assertEquals("5000", env.getProperty("gateway.circuit.open-duration-millis"));
        log.info("[默认配置] max-in-flight={} half-open-max-probes=1 failure-threshold=3",
                limiter.maxInFlight());
    }

    @Test
    void overloadIsReproducibleWithDefaultConfig() throws Exception {
        // 延迟低于默认上游超时 300ms（请求会成功），但远大于线程调度窗口，
        // 保证齐发请求在途重叠，稳定压过默认在途上限 4
        backend.setLatencyMillis(OVERLOAD_KEY, 150L);

        int threads = 12;
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(threads);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        List<GatewayResult> results = java.util.Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    results.add(gateway.route(
                            new GatewayRequest("orders", "v2", "GET", "\"x\""), CALLER, false));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(15, TimeUnit.SECONDS), "并发请求不得排队堆积");
        pool.shutdown();

        long ok = results.stream().filter(GatewayResult::isSuccess).count();
        List<GatewayResult> rejected = results.stream()
                .filter(r -> r.getErrorCode() == ErrorCode.CONCURRENCY_LIMIT_EXCEEDED).toList();
        assertEquals(threads, ok + rejected.size(),
                "每个请求要么放行要么过载快速拒绝，不得排队");
        assertTrue(ok <= limiter.maxInFlight(),
                "同时在途不得超过默认上限 " + limiter.maxInFlight() + "，实际放行 " + ok);
        assertTrue(rejected.size() >= 1, "默认配置齐发必须稳定产生过载快速拒绝");
        rejected.forEach(r -> {
            assertNotEquals(ErrorCode.QUOTA_EXCEEDED, r.getErrorCode(),
                    "过载与配额耗尽必须可区分");
            assertEquals(429, r.getHttpStatus());
        });
        log.info("[默认配置过载复现] 默认上限={}，齐发 {}：放行 {}，快速拒绝 {}",
                limiter.maxInFlight(), threads, ok, rejected.size());

        awaitCondition("名额应在请求结束后全部释放",
                () -> limiter.current(CALLER + ":orders") == 0, 5_000);
        GatewayResult after = gateway.route(
                new GatewayRequest("orders", "v2", "GET", "\"x\""), CALLER, false);
        assertTrue(after.isSuccess(), "压力回落后自动恢复放行");
        log.info("[过载恢复] 名额释放后请求正常放行");
    }

    @Test
    void halfOpenLimitedProbingIsReproducibleWithDefaultConfig() throws Exception {
        // 默认 failure-threshold=3：3 次连续失败（POST 不重试）打开熔断器
        backend.configure(RECOVERY_KEY, UpstreamFailureKind.ERROR_5XX, 3);
        for (int i = 0; i < 3; i++) {
            gateway.route(new GatewayRequest("users", "v1", "POST", "\"x\""), CALLER, false);
        }
        assertEquals(CircuitState.OPEN, breaker.state(RECOVERY_KEY));

        // 隔离期拒绝：UPSTREAM_UNAVAILABLE，不触达上游
        GatewayResult isolated = gateway.route(
                new GatewayRequest("users", "v1", "GET", "\"x\""), CALLER, false);
        assertEquals(ErrorCode.UPSTREAM_UNAVAILABLE, isolated.getErrorCode());
        assertEquals(3, backend.callCount(RECOVERY_KEY));

        // 默认 open-duration=5000：条件轮询等待进入 HALF_OPEN（不掐固定时间断言）
        awaitCondition("熔断冷却后应进入 HALF_OPEN",
                () -> breaker.state(RECOVERY_KEY) == CircuitState.HALF_OPEN, 15_000);
        log.info("[默认配置恢复期复现] 熔断器进入 HALF_OPEN");

        // 上游已恢复；探测请求带延迟（低于默认超时 300ms，探测会成功）以占住默认 1 个探测名额
        backend.clearFaults();
        backend.setLatencyMillis(RECOVERY_KEY, 200L);
        int beforeProbe = backend.callCount(RECOVERY_KEY);
        CompletableFuture<GatewayResult> probe = CompletableFuture.supplyAsync(() ->
                gateway.route(new GatewayRequest("users", "v1", "GET", "\"x\""), CALLER, false));
        awaitCondition("探测请求应真正触达上游",
                () -> backend.callCount(RECOVERY_KEY) == beforeProbe + 1, 5_000);

        // 探测名额已用完：其余请求拿到与隔离期拒绝可区分的结果，且不触达上游
        for (int i = 0; i < 3; i++) {
            GatewayResult limited = gateway.route(
                    new GatewayRequest("users", "v1", "GET", "\"x\""), CALLER, false);
            assertEquals(ErrorCode.UPSTREAM_RECOVERY_LIMITED, limited.getErrorCode(),
                    "恢复期名额耗尽必须可区分");
            assertNotEquals(ErrorCode.UPSTREAM_UNAVAILABLE, limited.getErrorCode());
            assertTrue(limited.isDegraded());
        }
        assertEquals(beforeProbe + 1, backend.callCount(RECOVERY_KEY),
                "恢复期只有拿到名额的探测真正打到上游，其余不打上游");
        log.info("[恢复期名额耗尽] 3 个并发请求全部 UPSTREAM_RECOVERY_LIMITED，触达数保持 {}",
                backend.callCount(RECOVERY_KEY));

        // 探测成功 -> 自动恢复正常放行
        GatewayResult probeResult = probe.get(10, TimeUnit.SECONDS);
        assertTrue(probeResult.isSuccess());
        awaitCondition("探测成功后熔断器应回到 CLOSED",
                () -> breaker.state(RECOVERY_KEY) == CircuitState.CLOSED, 5_000);
        GatewayResult after = gateway.route(
                new GatewayRequest("users", "v1", "GET", "\"x\""), CALLER, false);
        assertTrue(after.isSuccess(), "探测成功后自动恢复正常放行");
        log.info("[恢复完成] CLOSED，全过程触达上游 {} 次（无额外触达、无重复副作用）",
                backend.callCount(RECOVERY_KEY));
    }
}
