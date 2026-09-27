package com.github.lisama569.apigatewayjava.upstream;

public enum UpstreamFailureKind {
    TIMEOUT,
    ERROR_5XX,
    /** 批量调用中部分子请求失败（仍有成功项）。 */
    PARTIAL_FAILURE,
    /** 批量调用中全部子请求失败（无成功项），与部分失败严格区分。 */
    BATCH_FAILURE,
    /** 熔断器打开（隔离期）：请求被挡回，未触达上游，应继续退避。 */
    UPSTREAM_UNAVAILABLE,
    /** 恢复期（HALF_OPEN）探测名额已用尽：未触达上游，但可尽快重试。 */
    RECOVERY_LIMITED
}
