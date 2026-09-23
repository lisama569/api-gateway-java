package com.github.lisama569.apigatewayjava.model;

/**
 * Static description of one registered resource version.
 *
 * @param version   version identifier, e.g. "v1"
 * @param resource  resource name, e.g. "orders"
 * @param status    lifecycle status
 * @param upstream  upstream handler key used to serve this version
 * @param idempotent whether the upstream operation is safe to retry
 * @param sunsetNote human-readable note for removed/deprecated versions
 */
public record VersionDescriptor(
        String version,
        String resource,
        VersionStatus status,
        String upstream,
        boolean idempotent,
        String sunsetNote
) {
}
