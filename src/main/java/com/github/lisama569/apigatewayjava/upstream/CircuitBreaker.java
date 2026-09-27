package com.github.lisama569.apigatewayjava.upstream;

public interface CircuitBreaker {

    /**
     * 请求进入前的准入判定。
     *
     * @return {@link Admission#ALLOWED} 正常放行（CLOSED）或获得一个半开探测名额；
     *         {@link Admission#REJECTED_OPEN} 熔断打开、快速降级，不触达上游；
     *         {@link Admission#REJECTED_RECOVERY_LIMITED} 恢复期探测名额已用完，
     *         快速降级，不触达上游。
     */
    Admission allowRequest(String key);

    void recordSuccess(String key);

    void recordFailure(String key);

    CircuitState state(String key);

    void reset(String key);

    /** 准入结论：区分「熔断打开」与「恢复期探测名额已满」两种快速降级。 */
    enum Admission {
        ALLOWED,
        REJECTED_OPEN,
        REJECTED_RECOVERY_LIMITED
    }
}
