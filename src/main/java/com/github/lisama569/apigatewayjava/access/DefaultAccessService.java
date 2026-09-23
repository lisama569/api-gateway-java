package com.github.lisama569.apigatewayjava.access;

import com.github.lisama569.apigatewayjava.core.error.ErrorCode;
import com.github.lisama569.apigatewayjava.identity.CallerDirectory;
import com.github.lisama569.apigatewayjava.identity.CallerProfile;
import com.github.lisama569.apigatewayjava.identity.InMemoryCallerDirectory;
import com.github.lisama569.apigatewayjava.quota.QuotaDecision;
import com.github.lisama569.apigatewayjava.quota.QuotaService;
import org.springframework.stereotype.Component;

@Component
public class DefaultAccessService implements AccessService {

    private final CallerDirectory callerDirectory;
    private final QuotaService quotaService;

    public DefaultAccessService(CallerDirectory callerDirectory, QuotaService quotaService) {
        this.callerDirectory = callerDirectory;
        this.quotaService = quotaService;
    }

    @Override
    public AccessResult check(String callerId, String resource) {
        // 1) 身份认证：缺失/空白/未登记 -> IDENTITY_INVALID(401)
        CallerProfile profile;
        if (callerId == null || callerId.isBlank()) {
            return AccessResult.reject(ErrorCode.IDENTITY_INVALID,
                    "缺少调用方身份：请通过 X-Caller-Id 请求头提供");
        }
        profile = callerDirectory.find(callerId).orElse(null);
        if (profile == null) {
            return AccessResult.reject(ErrorCode.IDENTITY_INVALID,
                    "调用方身份无法识别：'" + callerId + "' 未登记");
        }

        // 2) 资源授权：身份有效但不在允许集合 -> IDENTITY_FORBIDDEN(403)
        boolean allowed = profile.allowedResources().contains(InMemoryCallerDirectory.ALL_RESOURCES)
                || profile.allowedResources().contains(resource);
        if (!allowed) {
            return AccessResult.reject(ErrorCode.IDENTITY_FORBIDDEN,
                    "调用方 '" + callerId + "' 无权访问资源 '" + resource
                            + "'，允许的资源：" + profile.allowedResources());
        }

        // 3) 配额判定：授权通过但窗口配额耗尽 -> QUOTA_EXCEEDED(429)
        QuotaDecision decision = quotaService.tryAcquire(callerId, profile.quotaLimit());
        if (!decision.allowed()) {
            return AccessResult.reject(ErrorCode.QUOTA_EXCEEDED, decision.reason());
        }
        return AccessResult.ok(profile);
    }
}
