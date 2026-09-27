package com.github.lisama569.apigatewayjava.upstream;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 内存版「上游服务」。本地测试无需外部依赖：
 * <ul>
 *   <li>{@link #configure} 让某个 key 的接下来 remain 次调用抛出指定类型故障；</li>
 *   <li>{@link #setLatencyMillis} 让某个 key 的调用产生延迟（用于模拟超时）；</li>
 *   <li>请求级 {@code demoFaultKind / demoLatencyMillis}：单次调用注入故障/延迟，
 *       用于本地压测复现故障、熔断、批量部分失败；</li>
 *   <li>{@link #blockOnGate} / {@link #awaitEntered} / {@link #releaseGate}：
 *       阻塞式并发闸门，测试可精确等待 N 个请求真正进入上游后再放行，
 *       不依赖固定 sleep，断言稳定不偶发；</li>
 *   <li>{@link #callCount} 统计真实触达上游的次数，用于断言重试/熔断期间没有产生重复业务副作用。</li>
 * </ul>
 */
@Component
public class InMemoryBackend {

    private final Map<String, Fault> faults = new ConcurrentHashMap<>();
    private final Map<String, Long> latencies = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> counters = new ConcurrentHashMap<>();

    /**
     * 阻塞闸门：blocking=true 时，进入后端的调用都会在各自的 ticket 上阻塞等待。
     * {@link #releaseOneGate()} 放行一个在途调用（其余继续阻塞），
     * {@link #releaseGate()} 一次性放行全部。由此可以「保持 N 个在途名额被占」
     * 直到测试显式放行，避免靠延迟/抢占制造并发、断言偶发。
     */
    private volatile boolean blocking = false;
    private final Object gateLock = new Object();
    private final java.util.ArrayDeque<CountDownLatch> tickets = new java.util.ArrayDeque<>();
    /** 已进入闸门（即已真实触达）的调用计数，测试用它等待目标在途数。 */
    private final AtomicInteger entered = new AtomicInteger(0);

    public UpstreamResponse invoke(UpstreamRequest request) {
        String key = request.key();
        counters.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();

        if (blocking) {
            CountDownLatch ticket;
            synchronized (gateLock) {
                ticket = new CountDownLatch(1);
                tickets.add(ticket);
            }
            entered.incrementAndGet();
            try {
                ticket.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new UpstreamException(UpstreamFailureKind.TIMEOUT, "上游调用被中断");
            }
        }

        long latency = Math.max(latencies.getOrDefault(key, 0L), request.demoLatencyMillis());
        if (latency > 0) {
            try {
                Thread.sleep(latency);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new UpstreamException(UpstreamFailureKind.TIMEOUT, "上游调用被中断");
            }
        }

        // 请求级演示故障优先（本地复现用），其次是 key 级注入故障
        if (request.demoFaultKind() != null) {
            throw new UpstreamException(request.demoFaultKind(),
                    "上游 key='" + key + "' 单次演示故障：" + request.demoFaultKind());
        }
        Fault fault = faults.get(key);
        if (fault != null && fault.remaining.decrementAndGet() >= 0) {
            throw new UpstreamException(fault.kind,
                    "上游 key='" + key + "' 注入故障：" + fault.kind);
        }
        return new UpstreamResponse(200,
                "{\"resource\":\"" + request.resource() + "\",\"version\":\"" + request.version()
                        + "\",\"echo\":" + (request.payload() == null ? "null" : request.payload()) + "}");
    }

    /**
     * 批量调用：按子请求逐个执行，某个子请求的故障不影响其他子请求，
     * 由上层归一化为 PARTIAL_FAILURE。
     */
    public List<UpstreamResponse> invokeBatch(List<UpstreamRequest> requests) {
        List<UpstreamResponse> responses = new ArrayList<>();
        for (UpstreamRequest request : requests) {
            responses.add(invoke(request));
        }
        return responses;
    }

    /** 返回该 key 真实触达后端的次数。 */
    public int callCount(String key) {
        AtomicInteger counter = counters.get(key);
        return counter == null ? 0 : counter.get();
    }

    /** 注入故障：接下来 remain 次对该 key 的调用抛出指定异常。 */
    public void configure(String key, UpstreamFailureKind kind, int remain) {
        faults.put(key, new Fault(kind, new AtomicInteger(remain)));
    }

    /** 注入单次故障（便捷方法）。 */
    public void configure(String key, UpstreamFailureKind kind) {
        configure(key, kind, 1);
    }

    /** 配置该 key 每次调用的耗时（毫秒）。 */
    public void setLatencyMillis(String key, long millis) {
        latencies.put(key, millis);
    }

    /**
     * 安装阻塞闸门：之后进入后端的调用都会阻塞（每个调用一张 ticket），
     * 直到 {@link #releaseOneGate()} / {@link #releaseGate()}。
     * 配合 {@link #awaitEntered} 可确定性地「保持 N 个在途」，无需延迟与抢占。
     */
    public void blockOnGate() {
        synchronized (gateLock) {
            tickets.clear();
            entered.set(0);
            blocking = true;
        }
    }

    /** 等待至少 n 个调用真实进入后端（最多等待 timeoutMillis，用于失败即报错而非无限挂死）。 */
    public void awaitEntered(int n, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (entered.get() < n) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("等待 " + n + " 个请求进入上游超时，实际进入 "
                        + entered.get());
            }
            Thread.sleep(1L);
        }
    }

    /** 已真实进入后端（被闸门挡住）的调用数。 */
    public int enteredCount() {
        return entered.get();
    }

    /** 放行一个阻塞在闸门上的调用（其余继续阻塞），用于在保持在途名额被占时逐步放量。 */
    public void releaseOneGate() {
        CountDownLatch ticket;
        synchronized (gateLock) {
            ticket = tickets.pollFirst();
        }
        if (ticket != null) {
            ticket.countDown();
        }
    }

    /** 关闭闸门阻塞并放行所有阻塞中的调用。 */
    public void releaseGate() {
        java.util.List<CountDownLatch> pending;
        synchronized (gateLock) {
            blocking = false;
            pending = new java.util.ArrayList<>(tickets);
            tickets.clear();
        }
        pending.forEach(CountDownLatch::countDown);
    }

    /** 清除注入的故障与延迟，但保留调用计数（用于断言熔断/恢复全过程的触达次数）。 */
    public void clearFaults() {
        faults.clear();
        latencies.clear();
    }

    /** 同时清空调用计数与闸门（测试隔离用）。 */
    public void resetAll() {
        clearFaults();
        counters.clear();
        releaseGate();
        synchronized (gateLock) {
            tickets.clear();
            entered.set(0);
        }
    }

    private record Fault(UpstreamFailureKind kind, AtomicInteger remaining) {
    }
}
