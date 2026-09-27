package com.github.lisama569.apigatewayjava.quota;

/**
 * 配额判定结果。
 *
 * @param allowed  是否放行
 * @param used     判定完成后窗口内已用量（含本次，若放行）
 * @param limit    窗口上限
 * @param consumed 本次实际扣减的额度（拒绝时为 0，批量放行时为子请求数）
 * @param reason   可解释的判定依据
 */
public record QuotaDecision(boolean allowed, long used, long limit, long consumed, String reason) {

    /** 兼容单请求（成本=1）场景的便捷构造器。 */
    public QuotaDecision(boolean allowed, long used, long limit, String reason) {
        this(allowed, used, limit, allowed ? 1 : 0, reason);
    }

    /** 剩余额度。 */
    public long remaining() {
        return limit - used;
    }
}
