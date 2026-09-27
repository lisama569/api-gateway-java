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
 * --等待 openDurationMillis--> HALF_OPEN（只放行有限个探测请求）--探测成功--> CLOSED；
 * 探测失败则重新回到 OPEN。
 *
 * 恢复期并发保护：HALF_OPEN 下最多同时放行 halfOpenMaxProbes 个探测请求真正
 * 触达上游，其余并发请求直接快速失败（视为降级），避免上游刚恢复时被
 * 并发流量再次打垮。探测全部结算前状态保持 HALF_OPEN。
 *
 * 每个上游独立统计、独立熔断，实现故障隔离；所有状态迁移在 per-key 锁内完成，
 * 保证并发下放行的探测数绝不超过配置上限。
 */
@Component
public class SlidingWindowCircuitBreaker implements CircuitBreaker {

    private final int failureThreshold;
    private final long openDurationMillis;
    private final int halfOpenMaxProbes;
    private final LongSupplier clock;
    private final ConcurrentHashMap<String, Breaker> breakers = new ConcurrentHashMap<>();

    @Autowired
    public SlidingWindowCircuitBreaker(
            @Value("${gateway.circuit.failure-threshold:3}") int failureThreshold,
            @Value("${gateway.circuit.open-duration-millis:5000}") long openDurationMillis,
            @Value("${gateway.circuit.half-open-max-probes:1}") int halfOpenMaxProbes) {
        this(failureThreshold, openDurationMillis, halfOpenMaxProbes, System::currentTimeMillis);
    }

    /** 兼容构造器：默认半开只放行 1 个探测。 */
    public SlidingWindowCircuitBreaker(int failureThreshold, long openDurationMillis, LongSupplier clock) {
        this(failureThreshold, openDurationMillis, 1, clock);
    }

    /** 测试用构造器。 */
    public SlidingWindowCircuitBreaker(int failureThreshold, long openDurationMillis,
                                       int halfOpenMaxProbes, LongSupplier clock) {
        this.failureThreshold = failureThreshold;
        this.openDurationMillis = openDurationMillis;
        this.halfOpenMaxProbes = halfOpenMaxProbes;
        this.clock = clock;
    }

    private Breaker breaker(String key) {
        return breakers.computeIfAbsent(key, k -> new Breaker());
    }

    @Override
    public boolean allowRequest(String key) {
        return tryEnter(key) == CircuitDecision.ALLOWED;
    }

    @Override
    public CircuitDecision tryEnter(String key) {
        Breaker b = breaker(key);
        synchronized (b) {
            long now = clock.getAsLong();
            if (b.state == CircuitState.OPEN && now - b.openedAtMillis >= openDurationMillis) {
                // 进入半开：开始放行有限个探测请求
                b.state = CircuitState.HALF_OPEN;
                b.inFlightProbes = 0;
            }
            if (b.state == CircuitState.HALF_OPEN) {
                // 只放行有限个探测；其余并发请求快速失败（降级），不触达上游。
                // 这种拒绝与 OPEN 拒绝严格区分：调用方可据此判断上游已在恢复期
                if (b.inFlightProbes >= halfOpenMaxProbes) {
                    return CircuitDecision.REJECTED_HALF_OPEN;
                }
                b.inFlightProbes++;
                return CircuitDecision.ALLOWED;
            }
            return b.state == CircuitState.OPEN
                    ? CircuitDecision.REJECTED_OPEN : CircuitDecision.ALLOWED;
        }
    }

    @Override
    public void recordSuccess(String key) {
        Breaker b = breaker(key);
        synchronized (b) {
            if (b.state == CircuitState.HALF_OPEN) {
                // 探测成功：自动恢复正常路径
                b.state = CircuitState.CLOSED;
                b.inFlightProbes = 0;
            }
            b.consecutiveFailures = 0;
        }
    }

    @Override
    public void recordFailure(String key) {
        Breaker b = breaker(key);
        synchronized (b) {
            if (b.state == CircuitState.HALF_OPEN) {
                // 探测失败：重新打开并重新计时，继续保持隔离
                b.state = CircuitState.OPEN;
                b.openedAtMillis = clock.getAsLong();
                b.inFlightProbes = 0;
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
        private int inFlightProbes = 0;
    }
}
