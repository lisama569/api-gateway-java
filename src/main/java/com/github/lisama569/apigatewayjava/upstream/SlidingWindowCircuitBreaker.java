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
 * --等待 openDurationMillis--> HALF_OPEN（只放行有限数量的探测请求）
 * --探测成功--> CLOSED；探测失败则重新回到 OPEN 并重新计时。
 *
 * 恢复期并发放量控制：进入 HALF_OPEN 的那一刻只发放 {@code halfOpenPermits} 个探测名额
 * （默认 1，可配置）。恢复期内并发请求中只有拿到名额的有限几个会真正触达上游，
 * 其余立即返回 {@link Admission#REJECTED_RECOVERY_LIMITED}（快速降级，不触达上游、无副作用），
 * 从而避免上游刚恢复时被一拥而上打垮。探测成功后自动回到 CLOSED 正常放行；
 * 探测失败则重新 OPEN 保持隔离，待冷却结束后再次发放探测名额。
 *
 * 每个上游独立统计、独立熔断，实现故障隔离；所有状态迁移与名额发放都在 per-key 锁内完成，
 * 保证并发下触达上游的探测数不超过配置上限。
 */
@Component
public class SlidingWindowCircuitBreaker implements CircuitBreaker {

    private final int failureThreshold;
    private final long openDurationMillis;
    private final int halfOpenPermits;
    private final LongSupplier clock;
    private final ConcurrentHashMap<String, Breaker> breakers = new ConcurrentHashMap<>();

    @Autowired
    public SlidingWindowCircuitBreaker(
            @Value("${gateway.circuit.failure-threshold:3}") int failureThreshold,
            @Value("${gateway.circuit.open-duration-millis:5000}") long openDurationMillis,
            @Value("${gateway.circuit.half-open-permits:1}") int halfOpenPermits) {
        this(failureThreshold, openDurationMillis, halfOpenPermits, System::currentTimeMillis);
    }

    /** 测试用构造器（保持历史三参签名）：恢复期探测名额默认 1。 */
    public SlidingWindowCircuitBreaker(int failureThreshold, long openDurationMillis, LongSupplier clock) {
        this(failureThreshold, openDurationMillis, 1, clock);
    }

    /** 测试用构造器：可自定义恢复期探测名额。 */
    public SlidingWindowCircuitBreaker(int failureThreshold, long openDurationMillis,
                                       int halfOpenPermits, LongSupplier clock) {
        if (halfOpenPermits < 1) {
            throw new IllegalArgumentException("halfOpenPermits 必须 >= 1");
        }
        this.failureThreshold = failureThreshold;
        this.openDurationMillis = openDurationMillis;
        this.halfOpenPermits = halfOpenPermits;
        this.clock = clock;
    }

    private Breaker breaker(String key) {
        return breakers.computeIfAbsent(key, k -> new Breaker());
    }

    @Override
    public Admission allowRequest(String key) {
        Breaker b = breaker(key);
        synchronized (b) {
            long now = clock.getAsLong();
            if (b.state == CircuitState.OPEN) {
                if (now - b.openedAtMillis < openDurationMillis) {
                    // 冷却未结束：保持隔离，不触达上游
                    return Admission.REJECTED_OPEN;
                }
                // 冷却结束：进入半开，并在同一把锁内一次性发放有限探测名额
                b.state = CircuitState.HALF_OPEN;
                b.remainingProbePermits = halfOpenPermits;
            }
            if (b.state == CircuitState.HALF_OPEN) {
                if (b.remainingProbePermits > 0) {
                    b.remainingProbePermits--;
                    return Admission.ALLOWED;
                }
                // 探测名额已用完：其余并发请求快速降级，不触达上游
                return Admission.REJECTED_RECOVERY_LIMITED;
            }
            return Admission.ALLOWED;
        }
    }

    @Override
    public void recordSuccess(String key) {
        Breaker b = breaker(key);
        synchronized (b) {
            b.state = CircuitState.CLOSED;
            b.consecutiveFailures = 0;
            b.remainingProbePermits = 0;
        }
    }

    @Override
    public void recordFailure(String key) {
        Breaker b = breaker(key);
        synchronized (b) {
            if (b.state == CircuitState.HALF_OPEN) {
                // 探测失败：重新打开并重新计时，保持隔离，待冷却后再发放探测名额
                b.state = CircuitState.OPEN;
                b.openedAtMillis = clock.getAsLong();
                b.remainingProbePermits = 0;
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
        private int remainingProbePermits = 0;
    }
}
