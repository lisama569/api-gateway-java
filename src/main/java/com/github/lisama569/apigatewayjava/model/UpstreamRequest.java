package com.github.lisama569.apigatewayjava.model;

/**
 * One upstream invocation.
 *
 * @param handler    upstream handler key
 * @param resource   resource being called
 * @param version    resolved version
 * @param idempotent whether the call may safely be retried
 * @param attempt    zero-based attempt number
 * @param payload    opaque request payload
 */
public record UpstreamRequest(
        String handler,
        String resource,
        String version,
        boolean idempotent,
        int attempt,
        String payload
) {
}
