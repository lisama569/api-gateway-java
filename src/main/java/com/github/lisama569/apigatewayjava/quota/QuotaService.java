package com.github.lisama569.apigatewayjava.quota;

public interface QuotaService {

    /** 单请求申请：成本固定为 1，保持既有行为。 */
    default QuotaDecision tryAcquire(String callerId, long limit) {
        return tryAcquire(callerId, limit, 1L);
    }

    /**
     * 按指定成本原子申请额度（批量调用时 cost = 子请求条数）。
     *
     * 全有或全无（all-or-nothing）：只有窗口剩余额度 &gt;= cost 时才一次性整体计入并放行；
     * 额度不足时整笔拒绝、计数器保持不变，绝不会出现「扣了一部分却放行了整批」的错乱。
     * 窗口翻转与「检查 + 增加 cost」在同一把 per-caller 锁内原子完成，
     * 因此并发下放行的子请求总量绝不超过窗口额度，且每个放行子请求都被计入。
     */
    QuotaDecision tryAcquire(String callerId, long limit, long cost);
}
