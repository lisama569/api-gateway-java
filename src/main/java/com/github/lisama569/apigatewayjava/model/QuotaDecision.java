package com.github.lisama569.apigatewayjava.model;

/**
 * A single quota evaluation for one caller in one window.
 *
 * @param callerKey  caller the decision belongs to
 * @param allowed    whether the request may proceed
 * @param used       slots already consumed in the current window (including this request when allowed)
 * @param limit      configured window limit
 * @param windowId   identifier of the current fixed window
 * @param reason     human-readable explanation of the decision
 */
public record QuotaDecision(
        String callerKey,
        boolean allowed,
        long used,
        long limit,
        long windowId,
        String reason
) {
}
