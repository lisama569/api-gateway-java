package com.github.lisama569.apigatewayjava.upstream;

/**
 * 一次上游调用。
 *
 * @param demoFaultKind   仅用于本地复现/演示的「本次调用故障类型」；
 *                        非 null 时本次真实触达将抛出对应故障（每次尝试都生效），
 *                        生产正常请求传 null。批量部分失败也靠它逐条注入。
 * @param demoLatencyMillis 仅用于本地复现/演示的「本次调用延迟」，>0 时 sleep。
 */
public record UpstreamRequest(String key, String method, String resource, String version,
                              String payload, boolean idempotent,
                              UpstreamFailureKind demoFaultKind, long demoLatencyMillis) {

    /** 正常请求/既有测试用构造器：无演示故障与延迟。 */
    public UpstreamRequest(String key, String method, String resource, String version,
                           String payload, boolean idempotent) {
        this(key, method, resource, version, payload, idempotent, null, 0L);
    }

    public UpstreamRequest withDemoFault(UpstreamFailureKind kind, long latencyMillis) {
        return new UpstreamRequest(key, method, resource, version, payload, idempotent,
                kind, latencyMillis);
    }
}
