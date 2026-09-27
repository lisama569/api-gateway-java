package com.github.lisama569.apigatewayjava.upstream;

public enum UpstreamFailureKind {
    TIMEOUT,
    ERROR_5XX,
    /** 批量调用中部分子请求失败（仍有成功项）。 */
    PARTIAL_FAILURE,
    /** 批量调用中全部子请求失败（无成功项），与部分失败严格区分。 */
    BATCH_FAILURE,
    /** 熔断器 OPEN：隔离期快速失败，未触达上游。 */
    CIRCUIT_OPEN,
    /** 熔断器 HALF_OPEN：恢复期探测名额已用完而快速失败，未触达上游；
     *  与 CIRCUIT_OPEN 严格区分，调用方可据此判断上游已在恢复。 */
    HALF_OPEN_PROBE_LIMIT
}
