package com.github.lisama569.apigatewayjava.quota;

public interface QuotaService {

    /** 单请求获取配额（成本=1）。 */
    default QuotaDecision tryAcquire(String callerId, long limit) {
        return tryAcquire(callerId, limit, 1);
    }

    /**
     * 按成本获取配额：要么整批扣减 cost 并放行，要么一分不扣直接拒绝，
     * 不存在「扣了一部分却放行/拒绝」的中间态。并发下同一调用方的
     * 检查与扣减是原子的。
     */
    QuotaDecision tryAcquire(String callerId, long limit, long cost);
}
