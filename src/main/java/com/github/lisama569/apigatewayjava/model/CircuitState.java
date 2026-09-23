package com.github.lisama569.apigatewayjava.model;

/**
 * Circuit breaker states.
 */
public enum CircuitState {
    /** Requests flow to the upstream. */
    CLOSED,
    /** Requests fail fast without hitting the upstream. */
    OPEN,
    /** One trial request is allowed to probe recovery. */
    HALF_OPEN
}
