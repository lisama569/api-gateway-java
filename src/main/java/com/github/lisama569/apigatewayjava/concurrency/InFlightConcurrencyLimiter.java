package com.github.lisama569.apigatewayjava.concurrency;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * 基于信号量的在途并发限流器。
 *
 * 维度可配置（{@code gateway.concurrency.scope}）：
 * <ul>
 *   <li>{@code caller}（默认）：同一调用方对所有资源共享一个在途上限；</li>
 *   <li>{@code caller_resource}：按「调用方 + 资源」分别计量。</li>
 * </ul>
 *
 * 语义：{@link #tryAcquire} 使用非阻塞获取，拿不到名额立即返回 false（快速过载，不排队堆积）；
 * 请求结束后在 finally 中调用 {@link #release} 立即归还，压力回落后自动恢复放行。
 * 每个 key 一个公平信号量，配合 tryAcribute 不保证公平但保证总量不被突破。
 */
@Component
public class InFlightConcurrencyLimiter implements ConcurrencyLimiter {

    public static final String SCOPE_CALLER = "caller";
    public static final String SCOPE_CALLER_RESOURCE = "caller_resource";

    private final ConcurrentHashMap<String, Semaphore> permits = new ConcurrentHashMap<>();
    private final int maxInFlight;
    private final boolean perResource;

    @Autowired
    public InFlightConcurrencyLimiter(
            @Value("${gateway.concurrency.max-in-flight:8}") int maxInFlight,
            @Value("${gateway.concurrency.scope:caller}") String scope) {
        if (maxInFlight < 1) {
            throw new IllegalArgumentException("gateway.concurrency.max-in-flight 必须 >= 1");
        }
        this.maxInFlight = maxInFlight;
        this.perResource = SCOPE_CALLER_RESOURCE.equalsIgnoreCase(scope);
    }

    /** 测试用构造器：直接指定上限与维度。 */
    public InFlightConcurrencyLimiter(int maxInFlight, boolean perResource) {
        this.maxInFlight = maxInFlight;
        this.perResource = perResource;
    }

    private String key(String callerId, String resource) {
        return perResource ? callerId + "|" + resource : callerId;
    }

    private Semaphore semaphore(String k) {
        return permits.computeIfAbsent(k, ignored -> new Semaphore(maxInFlight, true));
    }

    @Override
    public boolean tryAcquire(String callerId, String resource) {
        String k = key(callerId, resource);
        boolean acquired = semaphore(k).tryAcquire();
        if (acquired) {
            return true;
        }
        return false;
    }

    @Override
    public void release(String callerId, String resource) {
        Semaphore s = permits.get(key(callerId, resource));
        if (s != null) {
            s.release();
        }
    }

    @Override
    public int inFlight(String callerId, String resource) {
        Semaphore s = permits.get(key(callerId, resource));
        return s == null ? 0 : maxInFlight - s.availablePermits();
    }

    @Override
    public void reset() {
        permits.clear();
    }
}
