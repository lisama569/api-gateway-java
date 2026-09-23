package com.github.lisama569.apigatewayjava.quota;

public interface QuotaService {
    QuotaDecision tryAcquire(String callerId, long limit);
}
