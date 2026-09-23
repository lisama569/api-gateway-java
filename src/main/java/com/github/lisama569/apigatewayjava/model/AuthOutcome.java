package com.github.lisama569.apigatewayjava.model;

/**
 * Result of authenticating and authorizing a caller.
 */
public enum AuthOutcome {
    /** Caller is known and permitted to call the resource. */
    ALLOWED,
    /** No credentials supplied or the caller is unknown. */
    INVALID_IDENTITY,
    /** Caller is authenticated but not permitted for the resource. */
    FORBIDDEN
}
