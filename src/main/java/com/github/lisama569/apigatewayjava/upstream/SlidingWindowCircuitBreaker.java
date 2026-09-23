package com.github.lisama569.apigatewayjava.upstream;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * 按上游 key 隔离的熔断器。
 *
 * 状态机：CLOSED（正常）--连续失败达阈值--> OPEN（快速失败，不触达上游）
 * --等待 openDurationMillis--> HALF_OPEN（放行一次探测请求）--成功--> CLOSED；
 * 探测失败则重新回到 OPEN。故障恢复后因此自动回到正常路径。
 *
 * 每个上游独立统计、独立熔断，实现故障隔离；所有状态迁移在 per-key 锁内完成，
 * 保证并发下只有一个探测请求被放行。
 */
@Component
public class SlidingWindowCircuitBreaker implements CircuitBreaker {

    private final int failureThreshold;
    private final long openDurationMillis;
    private final LongSupplier clock;
    private final ConcurrentHashMap<String, Breaker> breakers = new ConcurrentHashMap<>();

    @Autowired
    public SlidingWindowCircuitBreaker(
            @Value("${gateway.circuit.failure-threshold:3}") int failureThreshold,
            @Value("${gateway.circuit.open-duration-millis:5000}") long openDurationMillis) {
        this(failureThreshold, openDurationMillis, System::currentTimeMillis);
    }

    /** 测试用构造器。 */
    public SlidingWindowCircuitBreaker(int failureThreshold, long openDurationMillis, LongSupplier clock) {
        this.failureThreshold = failureThreshold;
        this.openDurationMillis = openDurationMillis;
        this.clock = clock;
    }

    private Breaker breaker(String key) {
        return breakers.computeIfAbsent(key, k -> new Breaker());
    }

    @Override
    public boolean allowRequest(String key) {
        Breaker b = breaker(key);
        synchronized (b) {
            long now = clock.getAsLong();
            if (b.state == CircuitState.OPEN && now - b.openedAtMillis >= openDurationMillis) {
                // 进入半开：仅放通一个探测请求
                b.state = CircuitState.HALF_OPEN;
            }
            return b.state != CircuitState.OPEN;
        }
    }

    @Override
    public void recordSuccess(String key) {
        Breaker b = breaker(key);
        synchronized (b) {
            b.state = CircuitState.CLOSED;
            b.consecutiveFailures = 0;
        }
    }

    @Override
    public void recordFailure(String key) {
        Breaker b = breaker(key);
        synchronized (b) {
            if (b.state == CircuitState.HALF_OPEN) {
                // 探测失败：重新打开
                b.state = CircuitState.OPEN;
                b.openedAtMillis = clock.getAsLong();
                return;
            }
            b.consecutiveFailures++;
            if (b.consecutiveFailures >= failureThreshold) {
                b.state = CircuitState.OPEN;
                b.openedAtMillis = clock.getAsLong();
            }
        }
    }

    @Override
    public CircuitState state(String key) {
        Breaker b = breaker(key);
        synchronized (b) {
            long now = clock.getAsLong();
            if (b.state == CircuitState.OPEN && now - b.openedAtMillis >= openDurationMillis) {
                return CircuitState.HALF_OPEN;
            }
            return b.state;
        }
    }

    @Override
    public void reset(String key) {
        breakers.remove(key);
    }

    private static final class Breaker {
        private CircuitState state = CircuitState.CLOSED;
        private int consecutiveFailures = 0;
        private long openedAtMillis = 0;
    }
}
