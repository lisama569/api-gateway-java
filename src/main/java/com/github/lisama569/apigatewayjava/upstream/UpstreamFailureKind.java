package com.github.lisama569.apigatewayjava.upstream;

public enum UpstreamFailureKind {
    TIMEOUT,
    ERROR_5XX,
    /** 批量调用中部分子请求失败（仍有成功项）。 */
    PARTIAL_FAILURE,
    /** 批量调用中全部子请求失败（无成功项），与部分失败严格区分。 */
    BATCH_FAILURE
}
