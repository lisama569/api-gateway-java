package com.github.lisama569.apigatewayjava.model;

/**
 * Result of a version lookup.
 */
public record VersionResolution(
        VersionOutcome outcome,
        VersionDescriptor descriptor,
        String message
) {
}
