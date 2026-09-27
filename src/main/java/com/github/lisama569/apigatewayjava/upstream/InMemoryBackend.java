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
 *   <li>{@link #callCount} 统计真实触达上游的次数，用于断言重试/熔断期间没有产生重复业务副作用。</li>
 * </ul>
 */
@Component
public class InMemoryBackend {

    private final Map<String, Fault> faults = new ConcurrentHashMap<>();
    private final Map<String, Long> latencies = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> counters = new ConcurrentHashMap<>();
    private final Map<String, Block> blocks = new ConcurrentHashMap<>();

    public UpstreamResponse invoke(UpstreamRequest request) {
        String key = request.key();
        counters.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();

        Block block = blocks.get(key);
        if (block != null) {
            // 测试钩子：进入真实后端后发信号，并等待放行，用于稳定制造「一个探测在途」的窗口
            block.entered.countDown();
            try {
                block.release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new UpstreamException(UpstreamFailureKind.TIMEOUT, "上游调用被中断");
            }
        }

        long latency = latencies.getOrDefault(key, 0L);
        if (latency > 0) {
            try {
                Thread.sleep(latency);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new UpstreamException(UpstreamFailureKind.TIMEOUT, "上游调用被中断");
            }
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

    /** 清除注入的故障与延迟，但保留调用计数（用于断言熔断/恢复全过程的触达次数）。 */
    public void clearFaults() {
        faults.clear();
        latencies.clear();
    }

    /** 同时清空调用计数（测试隔离用）。 */
    public void resetAll() {
        clearFaults();
        counters.clear();
        blocks.clear();
    }

    /**
     * 测试钩子：让对该 key 的调用在真正进入后端后阻塞，直到调用 {@link #releaseBlock(String)}。
     * 可通过 {@link #awaitEntered} 等待至少 {@code enteredCount} 个调用已进入后端，
     * 用于确定性地制造「探测请求在途、其余并发请求应被快速降级」的并发场景。
     */
    public void block(String key, int enteredCount) {
        blocks.put(key, new Block(new CountDownLatch(enteredCount), new CountDownLatch(1)));
    }

    /** 等待指定数量的调用已进入并阻塞在后端。 */
    public void awaitEntered(String key) throws InterruptedException {
        Block b = blocks.get(key);
        if (b != null) {
            b.entered.await();
        }
    }

    /** 放行所有阻塞在该 key 上的调用。 */
    public void releaseBlock(String key) {
        Block b = blocks.get(key);
        if (b != null) {
            b.release.countDown();
        }
    }

    private record Block(CountDownLatch entered, CountDownLatch release) {
    }

    private record Fault(UpstreamFailureKind kind, AtomicInteger remaining) {
    }
}
