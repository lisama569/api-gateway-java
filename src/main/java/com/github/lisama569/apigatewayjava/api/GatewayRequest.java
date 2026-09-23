package com.github.lisama569.apigatewayjava.api;

public record GatewayRequest(String resource, String version, String method, String payload) {
}
