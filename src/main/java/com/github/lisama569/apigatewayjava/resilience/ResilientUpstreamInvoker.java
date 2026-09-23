package com.github.lisama569.apigatewayjava.resilience;

import com.github.lisama569.apigatewayjava.model.UpstreamRequest;
import com.github.lisama569.apigatewayjava.model.UpstreamResult;
import com.github.lisama569.apigatewayjava.model.UpstreamResultType;
import com.github.lisama569.apigatewayjava.upstream.UpstreamClient;
import com.github.lisama569.apigatewayjava.upstream.UpstreamRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Executes upstream calls with a deadline, circuit breaking, safe retries and
 * deterministic degradation.
 *
 * <p>Failure normalization:
 * <ul>
 *   <li>deadline exceeded -> {@link UpstreamResultType#TIMEOUT}</li>
 *   <li>upstream business failure -> {@link UpstreamResultType#UPSTREAM_ERROR}</li>
 *   <li>mixed batch -> {@link UpstreamResultType#PARTIAL_FAILURE} (not retried)</li>
 *   <li>circuit open -> {@link UpstreamResultType#CIRCUIT_OPEN} with a local fallback;
 *       the upstream is never invoked, hence no side effect is possible</li>
 * </ul>
 */
@Component
public class ResilientUpstreamInvoker {

    private static final Logger log = LoggerFactory.getLogger("GATEWAY");

    static final long DEADLINE_MILLIS = 150L;
    static final String FALLBACK_BODY =
            "{\"degraded\":true,\"message\":\"temporarily unavailable; request not applied\"}";

    private final UpstreamRouter router;
    private final CircuitBreaker breaker;
    private final RetryPolicy retryPolicy;
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "upstream-call");
        t.setDaemon(true);
        return t;
    });

    public ResilientUpstreamInvoker(UpstreamRouter router,
                                    CircuitBreaker breaker,
                                    RetryPolicy retryPolicy) {
        this.router = router;
        this.breaker = breaker;
        this.retryPolicy = retryPolicy;
    }

    public UpstreamResult invoke(UpstreamRequest initial) {
        UpstreamClient client = router.route(initial.handler());
        if (client == null) {
            return UpstreamResult.degraded(UpstreamResultType.UPSTREAM_ERROR,
                    "no upstream client registered for handler '" + initial.handler() + "'", 0);
        }

        if (!breaker.allowRequest(initial.handler())) {
            log.info("[GATEWAY] upstream result: handler={} state=OPEN -> degraded fallback (no upstream call)",
                    initial.handler());
            return UpstreamResult.degraded(UpstreamResultType.CIRCUIT_OPEN,
                    "circuit open for upstream '" + initial.handler()
                            + "'; served a local fallback without invoking upstream", 0);
        }

        UpstreamRequest request = initial;
        while (true) {
            UpstreamResult result = callWithDeadline(client, request);
            log.info("[GATEWAY] upstream result: handler={} attempt={} type={} degraded={} sideEffects={} msg={}",
                    initial.handler(), request.attempt() + 1, result.type(),
                    result.degraded(), result.sideEffects(), result.message());

            if (result.type() == UpstreamResultType.SUCCESS
                    || result.type() == UpstreamResultType.PARTIAL_FAILURE) {
                // one logical gateway call = one outcome for the breaker
                breaker.recordSuccess(initial.handler());
                return result;
            }

            if (retryPolicy.canRetry(initial.idempotent(), result.type(), request.attempt())) {
                long backoff = retryPolicy.backoffMillis(request.attempt());
                sleep(backoff);
                request = new UpstreamRequest(initial.handler(), initial.resource(),
                        initial.version(), initial.idempotent(),
                        request.attempt() + 1, initial.payload());
                // before a retry, the breaker may have opened; in that case degrade
                if (!breaker.allowRequest(initial.handler())) {
                    log.info("[GATEWAY] upstream result: handler={} circuit opened during retries -> fallback",
                            initial.handler());
                    breaker.recordFailure(initial.handler());
                    return UpstreamResult.degraded(UpstreamResultType.CIRCUIT_OPEN,
                            "circuit opened while retrying upstream '" + initial.handler()
                                    + "'; fallback served", request.attempt() + 1);
                }
                continue;
            }
            // the logical call failed after exhausting retries: count one failure
            breaker.recordFailure(initial.handler());
            return result;
        }
    }

    private UpstreamResult callWithDeadline(UpstreamClient client, UpstreamRequest request) {
        Future<UpstreamResult> future = executor.submit(() -> client.invoke(request));
        try {
            return future.get(DEADLINE_MILLIS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            return new UpstreamResult(UpstreamResultType.TIMEOUT, null, request.attempt() + 1,
                    false, 0, "upstream exceeded the " + DEADLINE_MILLIS + "ms deadline");
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            return new UpstreamResult(UpstreamResultType.UPSTREAM_ERROR, null,
                    request.attempt() + 1, false, 0,
                    "upstream call failed: " + cause.getClass().getSimpleName() + ": " + cause.getMessage());
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
