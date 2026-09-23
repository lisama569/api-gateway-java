package com.github.lisama569.apigatewayjava.model;

/**
 * Normalized outcome categories of an upstream call.
 */
public enum UpstreamResultType {
    /** Upstream produced a business response. */
    SUCCESS,
    /** Upstream did not answer within the deadline. */
    TIMEOUT,
    /** Upstream answered but reported a business failure. */
    UPSTREAM_ERROR,
    /** A batch completed with a mix of successes and failures. */
    PARTIAL_FAILURE,
    /** The circuit breaker rejected the call before it reached the upstream. */
    CIRCUIT_OPEN
}
