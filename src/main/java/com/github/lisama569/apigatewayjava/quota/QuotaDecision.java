package com.github.lisama569.apigatewayjava.quota;

public record QuotaDecision(boolean allowed, long used, long limit, String reason) {
}
