package com.github.lisama569.apigatewayjava.resilience;

import com.github.lisama569.apigatewayjava.model.CircuitState;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-upstream circuit breaker.
 *
 * <p>Transitions:
 * <ul>
 *   <li>CLOSED: all requests pass; {@link #FAILURE_THRESHOLD} consecutive failures open it</li>
 *   <li>OPEN: requests fail fast for {@link #OPEN_COOLDOWN_MILLIS}; the upstream is not called,
 *       so no business side effects can occur</li>
 *   <li>HALF_OPEN: exactly one probe request is allowed; success closes the breaker,
 *       failure re-opens it. Recovery is therefore automatic.</li>
 * </ul>
 */
@Component
public class CircuitBreaker {

    static final int FAILURE_THRESHOLD = 3;
    static final long OPEN_COOLDOWN_MILLIS = 1_000L;
    static final int HALF_OPEN_SUCCESSES_TO_CLOSE = 1;

    private final ConcurrentHashMap<String, Breaker> breakers = new ConcurrentHashMap<>();

    public boolean allowRequest(String upstream) {
        return breaker(upstream).allowRequest();
    }

    public void recordSuccess(String upstream) {
        breaker(upstream).recordSuccess();
    }

    public void recordFailure(String upstream) {
        breaker(upstream).recordFailure();
    }

    public CircuitState state(String upstream) {
        return breaker(upstream).state();
    }

    public void reset(String upstream) {
        breakers.remove(upstream);
    }

    private Breaker breaker(String upstream) {
        return breakers.computeIfAbsent(upstream, k -> new Breaker());
    }

    private static final class Breaker {
        private CircuitState state = CircuitState.CLOSED;
        private int consecutiveFailures;
        private int halfOpenSuccesses;
        private long openedAt;
        private boolean probeInFlight;

        synchronized boolean allowRequest() {
            if (state == CircuitState.CLOSED) {
                return true;
            }
            if (state == CircuitState.OPEN) {
                if (System.currentTimeMillis() - openedAt >= OPEN_COOLDOWN_MILLIS) {
                    state = CircuitState.HALF_OPEN;
                    halfOpenSuccesses = 0;
                    probeInFlight = true;
                    return true;
                }
                return false;
            }
            // HALF_OPEN: the single probe was already dispatched; others fail fast
            return false;
        }

        synchronized void recordSuccess() {
            if (state == CircuitState.HALF_OPEN) {
                halfOpenSuccesses++;
                if (halfOpenSuccesses >= HALF_OPEN_SUCCESSES_TO_CLOSE) {
                    toClosed();
                }
            } else if (state == CircuitState.CLOSED) {
                consecutiveFailures = 0;
            }
        }

        synchronized void recordFailure() {
            probeInFlight = false;
            if (state == CircuitState.HALF_OPEN) {
                open();
                return;
            }
            consecutiveFailures++;
            if (consecutiveFailures >= FAILURE_THRESHOLD) {
                open();
            }
        }

        synchronized CircuitState state() {
            if (state == CircuitState.OPEN
                    && System.currentTimeMillis() - openedAt >= OPEN_COOLDOWN_MILLIS) {
                return CircuitState.HALF_OPEN;
            }
            return state;
        }

        private void open() {
            state = CircuitState.OPEN;
            openedAt = System.currentTimeMillis();
            consecutiveFailures = 0;
            halfOpenSuccesses = 0;
            probeInFlight = false;
        }

        private void toClosed() {
            state = CircuitState.CLOSED;
            consecutiveFailures = 0;
            halfOpenSuccesses = 0;
            probeInFlight = false;
        }
    }
}
