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
    /**
     * 身份与权限均有效、配额也未用尽，但该调用方（或调用方+资源）的在途并发已达上限：
     * 与 {@link #QUOTA_EXCEEDED}（窗口额度耗尽）是两种不同的过载原因。
     */
    CALLER_OVERLOADED(429),
    /** 上游在限定时间内未返回：可重试类故障（仅幂等请求实际重试）。 */
    UPSTREAM_TIMEOUT(504),
    /** 批量调用中部分子请求失败：整体返回 207 风格的混合结果，逐条可区分。 */
    UPSTREAM_PARTIAL_FAILURE(207),
    /** 批量调用中全部子请求均失败：与「部分成功」严格区分，调用方应整批重试（或按失败项处理）。 */
    UPSTREAM_BATCH_ALL_FAILED(502),
    /** 熔断器打开，请求被快速失败/降级，未触达上游：避免重复业务副作用。 */
    UPSTREAM_UNAVAILABLE(503),
    /**
     * 熔断恢复期（HALF_OPEN）在途名额已满：只有有限数量的探测请求会真正触达上游，
     * 其余请求快速降级返回（未触达上游），探测成功后自动恢复正常放行。
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
