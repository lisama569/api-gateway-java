package com.github.lisama569.apigatewayjava.upstream;

public interface CircuitBreaker {
    boolean allowRequest(String key);

    /**
     * 带原因的放行判定：与 {@link #allowRequest} 语义一致，但能区分
     * 「熔断打开拒绝」与「恢复期探测名额耗尽拒绝」两种快速失败。
     * 默认实现保持兼容（无法区分时一律按 OPEN 拒绝上报）。
     */
    default CircuitDecision tryEnter(String key) {
        return allowRequest(key) ? CircuitDecision.ALLOWED : CircuitDecision.REJECTED_OPEN;
    }

    void recordSuccess(String key);

    void recordFailure(String key);

    CircuitState state(String key);

    void reset(String key);
}
