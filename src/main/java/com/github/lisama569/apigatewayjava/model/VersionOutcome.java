package com.github.lisama569.apigatewayjava.model;

/**
 * Result of resolving a requested version.
 */
public enum VersionOutcome {
    /** Version resolved successfully (active or deprecated). */
    OK,
    /** Version string is syntactically unknown to the gateway. */
    UNKNOWN_VERSION,
    /** Version is known but has been removed. */
    VERSION_REMOVED
}
