package com.github.lisama569.apigatewayjava.version;

public record VersionedResource(String resource, String version, VersionStatus status,
                                String deprecationNotice, String removalReason) {
}
