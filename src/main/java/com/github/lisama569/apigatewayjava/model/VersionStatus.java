package com.github.lisama569.apigatewayjava.model;

/**
 * Lifecycle status of an API version.
 */
public enum VersionStatus {
    /** Version is available and fully supported. */
    ACTIVE,
    /** Version still works but is announced for removal; responses carry a deprecation hint. */
    DEPRECATED,
    /** Version existed before but has been removed; requests must be rejected. */
    REMOVED
}
