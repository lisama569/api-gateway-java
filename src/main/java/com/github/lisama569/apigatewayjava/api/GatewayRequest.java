package com.github.lisama569.apigatewayjava.api;

/**
 * 进入网关的一次请求。
 *
 * @param demoFaultHeader   本地复现用：X-Demo-Fault 头（TIMEOUT / ERROR_5XX），正常为 null
 * @param demoLatencyMillis 本地复现用：X-Demo-Latency-Millis 头，正常为 0
 */
public record GatewayRequest(String resource, String version, String method, String payload,
                             String demoFaultHeader, long demoLatencyMillis) {

    /** 既有构造器：无演示故障/延迟。 */
    public GatewayRequest(String resource, String version, String method, String payload) {
        this(resource, version, method, payload, null, 0L);
    }
}
