package com.github.lisama569.apigatewayjava.resilience;

import com.github.lisama569.apigatewayjava.model.UpstreamResultType;
import org.springframework.stereotype.Component;

/**
 * Decides whether and how a failed call may be retried.
 *
 * <p>Safety rule: only idempotent operations may be retried, because a retry of a
 * non-idempotent operation could duplicate a business side effect that the upstream
 * actually committed. Partial failures are never retried: part of the batch has already
 * been applied, so a blind retry would duplicate it.
 */
@Component
public class RetryPolicy {

    /** Maximum physical attempts (initial attempt inclusive). */
    static final int MAX_ATTEMPTS = 2;
    static final long BACKOFF_BASE_MILLIS = 50L;

    public boolean canRetry(boolean idempotent, UpstreamResultType failureType, int attempt) {
        if (!idempotent) {
            return false;
        }
        boolean retryable = failureType == UpstreamResultType.TIMEOUT
                || failureType == UpstreamResultType.UPSTREAM_ERROR;
        return retryable && attempt + 1 < MAX_ATTEMPTS;
    }

    public long backoffMillis(int attempt) {
        return BACKOFF_BASE_MILLIS * (1L << Math.max(0, attempt));
    }
}
