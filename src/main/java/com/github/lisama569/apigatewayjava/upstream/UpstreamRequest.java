package com.github.lisama569.apigatewayjava.upstream;

public record UpstreamRequest(String key, String method, String resource, String version,
                              String payload, boolean idempotent) {
}
