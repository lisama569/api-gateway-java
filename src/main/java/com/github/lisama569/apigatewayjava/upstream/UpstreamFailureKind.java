package com.github.lisama569.apigatewayjava.upstream;

public enum UpstreamFailureKind {
    TIMEOUT,
    ERROR_5XX,
    PARTIAL_FAILURE,
    /** 熔断打开：请求未触达上游，被快速降级。 */
    BREAKER_OPEN,
    /** 熔断恢复期探测名额已满：请求未触达上游，被快速降级。 */
    RECOVERY_LIMITED
}
