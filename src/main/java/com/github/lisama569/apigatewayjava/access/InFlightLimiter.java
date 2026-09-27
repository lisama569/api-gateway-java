package com.github.lisama569.apigatewayjava.access;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 按调用方（或调用方+资源）的在途并发上限。
 *
 * 与配额的区别：配额是「时间窗内总量」约束，耗尽后需等窗口翻转；
 * 在途上限是「同一时刻并发数」约束，请求结束立即释放名额，
 * 压力回落后无需任何干预即自动恢复放行。
 *
 * 超过上限的请求快速失败（不排队、不堆积），返回可识别的
 * {@code CONCURRENCY_LIMIT_EXCEEDED}，与 {@code QUOTA_EXCEEDED} 严格区分。
 */
@Component
public class InFlightLimiter {

    private final int maxInFlight;
    private final ConcurrentHashMap<String, AtomicInteger> inFlight = new ConcurrentHashMap<>();

    @Autowired
    public InFlightLimiter(@Value("${gateway.concurrency.max-in-flight:4}") int maxInFlight) {
        this.maxInFlight = maxInFlight;
    }

    /** 当前配置的在途上限。 */
    public int maxInFlight() {
        return maxInFlight;
    }

    /**
     * 尝试占用一个名额：成功返回 true，调用方必须在请求结束后
     * 调用 {@link #release(String)}（建议 finally），否则名额泄漏。
     */
    public boolean tryAcquire(String key) {
        AtomicInteger counter = inFlight.computeIfAbsent(key, k -> new AtomicInteger());
        while (true) {
            int current = counter.get();
            if (current >= maxInFlight) {
                return false;
            }
            if (counter.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    /** 释放一个名额，压力回落后立即恢复放行。 */
    public void release(String key) {
        AtomicInteger counter = inFlight.get(key);
        if (counter != null) {
            counter.decrementAndGet();
        }
    }

    /** 当前在途数（测试与观测用）。 */
    public int current(String key) {
        AtomicInteger counter = inFlight.get(key);
        return counter == null ? 0 : counter.get();
    }
}
