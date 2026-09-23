package com.github.lisama569.apigatewayjava.version;

import java.util.Optional;

public interface VersionRegistry {
    Optional<VersionedResource> resolve(String resource, String version);
}
