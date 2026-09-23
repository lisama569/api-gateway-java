package com.github.lisama569.apigatewayjava.upstream;

public interface CircuitBreaker {
    boolean allowRequest(String key);

    void recordSuccess(String key);

    void recordFailure(String key);

    CircuitState state(String key);

    void reset(String key);
}
