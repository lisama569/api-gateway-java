package com.github.lisama569.apigatewayjava.upstream;

public interface CircuitBreaker {

    /**
     * 准入判定结果。两种拒绝均不触达上游，但语义不同：
     * <ul>
     *   <li>{@link #REJECTED_OPEN}：熔断器打开，上游仍在隔离期，调用方应继续退避；</li>
     *   <li>{@link #REJECTED_RECOVERY_LIMITED}：熔断器处于恢复期（HALF_OPEN），
     *       本轮有限探测名额已用尽，调用方可在极短退避后尽快重试。</li>
     * </ul>
     */
    enum Admission {
        ALLOWED,
        REJECTED_OPEN,
        REJECTED_RECOVERY_LIMITED
    }

    boolean allowRequest(String key);

    /**
     * 类型化准入判定：相比布尔版 {@link #allowRequest}，能区分
     * 「OPEN 挡回」与「恢复期名额用尽」两类快速失败。
     */
    Admission admit(String key);

    void recordSuccess(String key);

    void recordFailure(String key);

    CircuitState state(String key);

    void reset(String key);
}
