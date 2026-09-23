package com.github.lisama569.apigatewayjava.ratelimit;

import com.github.lisama569.apigatewayjava.model.QuotaDecision;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-caller fixed-window quota evaluator.
 *
 * <p>Concurrency contract: the window rollover check and the slot consumption are one
 * atomic critical section per caller. Therefore, under concurrent requests, at most
 * {@code limit} requests can be admitted in a window (never exceeded) and every admitted
 * request is counted exactly once (never missed).
 *
 * <p>Decisions are self-describing: used/limit/windowId and an explanation are returned so
 * callers and tests can see why a request was admitted or rejected.
 */
@Component
public class QuotaService {

    /** Fixed window length in milliseconds. */
    static final long WINDOW_MILLIS = 60_000L;

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    public QuotaDecision tryAcquire(String callerKey, long limit) {
        if (limit < 0) {
            throw new IllegalArgumentException("limit must be non-negative");
        }
        long windowId = System.currentTimeMillis() / WINDOW_MILLIS;
        return windows.computeIfAbsent(callerKey, k -> new Window()).acquire(callerKey, limit, windowId);
    }

    /** Clears the caller's current window (test/admin operation). */
    public void reset(String callerKey) {
        windows.remove(callerKey);
    }

    /**
     * One fixed window. All mutating access is synchronized on the window itself, making
     * "roll over -> check -> count" a single atomic step.
     */
    private static final class Window {
        private long windowId = -1L;
        private long used;

        synchronized QuotaDecision acquire(String callerKey, long limit, long currentWindow) {
            if (currentWindow != windowId) {
                windowId = currentWindow;
                used = 0L;
            }
            if (used >= limit) {
                return new QuotaDecision(callerKey, false, used, limit, windowId,
                        "quota exhausted for window " + windowId + ": used " + used
                                + " of " + limit + " (retry after window rollover)");
            }
            used++;
            return new QuotaDecision(callerKey, true, used, limit, windowId,
                    "quota slot granted: used " + used + " of " + limit
                            + " in window " + windowId);
        }
    }
}
