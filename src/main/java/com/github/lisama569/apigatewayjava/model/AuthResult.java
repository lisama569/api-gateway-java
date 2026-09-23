package com.github.lisama569.apigatewayjava.model;

/**
 * Authentication/authorization decision with an explanation.
 */
public record AuthResult(
        AuthOutcome outcome,
        Caller caller,
        String reason
) {
}
