package com.github.lisama569.apigatewayjava.quota;

/**
 * 一次配额判定结果。
 *
 * @param allowed 是否整笔放行
 * @param used    判定后窗口内已用额度（拒绝时不变，即拒绝前的已用值）
 * @param limit   当前窗口总额度
 * @param cost    本次申请消耗的子请求成本（单请求=1，批量=子请求条数）
 * @param reason  可解释、可复现的判定依据
 */
public record QuotaDecision(boolean allowed, long used, long limit, long cost, String reason) {

    /** 单成本（单请求）判定的便捷构造。 */
    public QuotaDecision(boolean allowed, long used, long limit, String reason) {
        this(allowed, used, limit, 1L, reason);
    }

    /** 放行后窗口内剩余可用额度。 */
    public long remaining() {
        return Math.max(0L, limit - used);
    }
}
