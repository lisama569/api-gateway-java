package com.github.lisama569.apigatewayjava.api;

/**
 * 批量调用的整体结果标识，调用方无需逐条比对即可一眼分清三种（含降级共四种）结果。
 * 仅批量响应携带该字段。
 */
public enum BatchStatus {
    /** 全部子请求成功（HTTP 200）。 */
    ALL_SUCCEEDED,
    /** 部分子请求失败（HTTP 207 + UPSTREAM_PARTIAL_FAILURE）：只需重试失败项。 */
    PARTIAL_FAILURE,
    /** 全部子请求失败（HTTP 502 + UPSTREAM_BATCH_FAILURE）：应整批重试，
     *  不得被当作部分成功。 */
    ALL_FAILED,
    /** 整批被快速降级（HTTP 503，熔断打开或恢复期探测名额耗尽）：
     *  未触达上游，逐条占位结果仍保留在 batchData 中。 */
    DEGRADED
}
