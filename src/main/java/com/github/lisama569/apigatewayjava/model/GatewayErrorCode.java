package com.github.lisama569.apigatewayjava.model;

/**
 * Stable, distinguishable error codes returned by the gateway.
 */
public enum GatewayErrorCode {
    VERSION_UNKNOWN,
    VERSION_REMOVED,
    AUTH_INVALID_IDENTITY,
    AUTH_FORBIDDEN,
    QUOTA_EXCEEDED,
    UPSTREAM_TIMEOUT,
    UPSTREAM_ERROR,
    UPSTREAM_PARTIAL_FAILURE,
    UPSTREAM_CIRCUIT_OPEN,
    UPSTREAM_DEGRADED
}
