package com.github.lisama569.apigatewayjava.model;

/**
 * Normalized upstream response.
 *
 * @param type        outcome category
 * @param body        response body when available
 * @param attempts    number of physical attempts made
 * @param degraded    true when the result is a local fallback rather than an upstream answer
 * @param sideEffects number of business side effects the upstream committed
 * @param message     explanation when not successful
 */
public record UpstreamResult(
        UpstreamResultType type,
        String body,
        int attempts,
        boolean degraded,
        int sideEffects,
        String message
) {

    public static UpstreamResult success(String body, int attempts, int sideEffects) {
        return new UpstreamResult(UpstreamResultType.SUCCESS, body, attempts, false, sideEffects, null);
    }

    public static UpstreamResult degraded(UpstreamResultType type, String message, int attempts) {
        return new UpstreamResult(type, null, attempts, true, 0, message);
    }
}
