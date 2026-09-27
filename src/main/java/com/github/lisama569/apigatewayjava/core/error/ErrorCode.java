package com.github.lisama569.apigatewayjava.core.error;

/**
 * 网关归一化后的错误分类。不同拒绝/失败原因映射为不同的错误码与 HTTP 状态，
 * 保证调用方能够明确区分「未知版本 / 已移除版本 / 身份无效 / 无权调用 /
 * 超出配额 / 上游超时 / 部分失败 / 熔断降级 / 上游错误」。
 */
public enum ErrorCode {
    /** 请求的资源版本从未登记：属于调用方错误，不可重试。 */
    VERSION_UNKNOWN(404),
    /** 版本曾存在但已被正式移除：与 UNKNOWN 区分，提示调用方迁移。 */
    VERSION_REMOVED(410),
    /** 调用方身份缺失或无法识别：未认证。 */
    IDENTITY_INVALID(401),
    /** 身份有效，但该调用方无权访问目标资源：禁止访问。 */
    IDENTITY_FORBIDDEN(403),
    /** 身份与权限均有效，但当前时间窗内配额已用尽：过多请求。 */
    QUOTA_EXCEEDED(429),
    /** 在途并发数达到上限：与配额耗尽不同的过载信号，压力回落后自动恢复。 */
    CONCURRENCY_LIMIT_EXCEEDED(429),
    /** 上游在限定时间内未返回：可重试类故障（仅幂等请求实际重试）。 */
    UPSTREAM_TIMEOUT(504),
    /** 批量调用中部分子请求失败：整体返回 207 风格的混合结果，逐条可区分。 */
    UPSTREAM_PARTIAL_FAILURE(207),
    /** 批量调用中全部子请求失败：与部分失败严格区分，调用方应整批重试。 */
    UPSTREAM_BATCH_FAILURE(502),
    /** 熔断器打开（上游仍在隔离期），请求被快速失败/降级，未触达上游：应继续退避。 */
    UPSTREAM_UNAVAILABLE(503),
    /**
     * 熔断器处于恢复期（HALF_OPEN），本轮有限探测名额已用尽：
     * 与 UPSTREAM_UNAVAILABLE 同为 503、均未触达上游，但含义不同——
     * 上游可能即将恢复，调用方可在极短退避后尽快重试，而非等待下一个冷却周期。
     */
    UPSTREAM_RECOVERY_LIMITED(503),
    /** 上游返回确定性 5xx 或其他错误。 */
    UPSTREAM_ERROR(502);

    private final int httpStatus;

    ErrorCode(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    public int httpStatus() {
        return httpStatus;
    }
}
