package com.github.lisama569.apigatewayjava.upstream;

import com.github.lisama569.apigatewayjava.model.UpstreamRequest;
import com.github.lisama569.apigatewayjava.model.UpstreamResult;
import com.github.lisama569.apigatewayjava.model.UpstreamResultType;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Local scripted upstream. Behavior is driven by the {@code scenario} carried in the
 * request payload (set by the gateway from the test header) and by health switches that
 * tests can flip directly.
 *
 * <p>Scenarios:
 * <ul>
 *   <li>{@code ok} / empty - normal success</li>
 *   <li>{@code timeout} - sleeps beyond the gateway deadline</li>
 *   <li>{@code error} - deterministic upstream business error</li>
 *   <li>{@code partial} - batch with one successful and one failed item</li>
 *   <li>{@code fail:N} - the first N physical attempts fail, later attempts succeed
 *       (used to verify retry of idempotent operations)</li>
 * </ul>
 *
 * <p>Global switch {@link #setDown(boolean)} makes every call fail until cleared,
 * simulating a continuous outage and subsequent recovery.
 *
 * <p>{@link #invocationCount} counts physical calls (including retries),
 * {@link #sideEffectCount} counts committed business effects. Successful idempotent
 * retries commit exactly one effect; failing attempts commit none.
 */
@Component
public class ScriptedUpstreamClient implements UpstreamClient {

    /** Simulated upstream latency for timeout scenarios; exceeds the gateway deadline. */
    static final long TIMEOUT_LATENCY_MILLIS = 400L;

    private final AtomicInteger invocations = new AtomicInteger();
    private final AtomicInteger sideEffects = new AtomicInteger();
    private final AtomicBoolean down = new AtomicBoolean(false);

    @Override
    public String key() {
        return "orders-upstream";
    }

    @Override
    public UpstreamResult invoke(UpstreamRequest request) {
        int callNo = invocations.incrementAndGet();
        String scenario = scenarioOf(request.payload());

        if ("timeout".equals(scenario)) {
            try {
                Thread.sleep(TIMEOUT_LATENCY_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            // no side effect: the effect, if any, is unknown after a timeout
            return new UpstreamResult(UpstreamResultType.TIMEOUT, null, request.attempt() + 1,
                    false, 0, "upstream did not respond before the deadline");
        }

        if (down.get() || "error".equals(scenario)) {
            return new UpstreamResult(UpstreamResultType.UPSTREAM_ERROR, null,
                    request.attempt() + 1, false, 0,
                    "upstream rejected the request (scenario=" + scenario
                            + ", down=" + down.get() + ")");
        }

        if ("partial".equals(scenario)) {
            // one item commits, one item fails
            sideEffects.incrementAndGet();
            return new UpstreamResult(UpstreamResultType.PARTIAL_FAILURE,
                    "{\"items\":[{\"id\":1,\"ok\":true},{\"id\":2,\"ok\":false}]}",
                    request.attempt() + 1, false, 1,
                    "batch partially applied: 1 of 2 items succeeded");
        }

        Integer failFirst = failFirstN(scenario);
        if (failFirst != null && callNo <= failFirst) {
            return new UpstreamResult(UpstreamResultType.UPSTREAM_ERROR, null,
                    request.attempt() + 1, false, 0,
                    "transient upstream error on attempt " + (request.attempt() + 1)
                            + " (recovers after " + failFirst + " failing calls)");
        }

        sideEffects.incrementAndGet();
        return UpstreamResult.success(
                "{\"resource\":\"" + request.resource() + "\",\"version\":\"" + request.version()
                        + "\",\"echo\":" + payloadJson(request.payload()) + ",\"upstreamCall\":" + callNo + "}",
                request.attempt() + 1, 1);
    }

    public int invocationCount() {
        return invocations.get();
    }

    public int sideEffectCount() {
        return sideEffects.get();
    }

    public void setDown(boolean value) {
        down.set(value);
    }

    public void reset() {
        invocations.set(0);
        sideEffects.set(0);
        down.set(false);
    }

    private static String scenarioOf(String payload) {
        if (payload == null || payload.isBlank()) {
            return "ok";
        }
        String trimmed = payload.trim();
        if (trimmed.startsWith("scenario:")) {
            return trimmed.substring("scenario:".length()).trim();
        }
        return "ok";
    }

    private static Integer failFirstN(String scenario) {
        if (scenario != null && scenario.startsWith("fail:")) {
            try {
                return Integer.parseInt(scenario.substring("fail:".length()));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String payloadJson(String payload) {
        return payload == null ? "null" : "\"" + payload.replace("\"", "'") + "\"";
    }
}
