package com.github.lisama569.apigatewayjava.quota;

import com.github.lisama569.apigatewayjava.core.time.GatewayClock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * 固定窗口配额服务。
 *
 * 并发性保证：每个调用方对应一个 {@link Window}，窗口翻转与计数自增在同一把
 * per-caller 监视器锁内完成（check-and-increment 原子化），因此在并发请求下：
 * <ul>
 *   <li>通过的请求数不会超过该窗口配额（不会被突破）；</li>
 *   <li>每个被放行的请求都会被计入（不会漏计）。</li>
 * </ul>
 * 判定结果携带 used/limit/reason，保证判定依据可解释。
 */
@Component
public class FixedWindowQuotaService implements QuotaService {

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final long windowMillis;
    private final LongSupplier clock;

    @Autowired
    public FixedWindowQuotaService(
            @Value("${gateway.quota.window-millis:60000}") long windowMillis,
            GatewayClock clock) {
        this(windowMillis, (java.util.function.LongSupplier) clock);
    }

    /** 测试用构造器：可注入时钟。 */
    public FixedWindowQuotaService(long windowMillis, LongSupplier clock) {
        this.windowMillis = windowMillis;
        this.clock = clock;
    }

    @Override
    public QuotaDecision tryAcquire(String callerId, long limit, long cost) {
        if (cost <= 0) {
            throw new IllegalArgumentException("配额成本必须为正数，实际：" + cost);
        }
        Window window = windows.computeIfAbsent(callerId, k -> new Window(clock.getAsLong()));
        long now = clock.getAsLong();
        // 窗口翻转 + 检查 + 按成本扣减必须在同一把锁内原子完成：
        // 要么整批扣减 cost，要么一分不扣，杜绝「扣了一部分却放行/拒绝」的错乱账目
        synchronized (window) {
            if (now - window.startMillis >= windowMillis) {
                window.startMillis = now;
                window.used = 0;
            }
            if (window.used + cost > limit) {
                return new QuotaDecision(false, window.used, limit, 0,
                        "配额不足：本次需消耗 " + cost + "，窗口内已使用 " + window.used
                                + "/" + limit + "，剩余 " + (limit - window.used)
                                + "（窗口长度 " + windowMillis + "ms），未扣减任何额度");
            }
            window.used += cost;
            return new QuotaDecision(true, window.used, limit, cost,
                    "配额内放行：本次消耗 " + cost + "，窗口内已使用 " + window.used
                            + "/" + limit + "，剩余 " + (limit - window.used));
        }
    }

    /** 清空所有窗口（测试用）。 */
    public void reset() {
        windows.clear();
    }

    private static final class Window {
        private volatile long startMillis;
        private long used;

        private Window(long startMillis) {
            this.startMillis = startMillis;
        }
    }
}
