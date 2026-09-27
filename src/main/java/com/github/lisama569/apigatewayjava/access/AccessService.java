package com.github.lisama569.apigatewayjava.access;

import com.github.lisama569.apigatewayjava.core.error.ErrorCode;
import com.github.lisama569.apigatewayjava.identity.CallerProfile;
import com.github.lisama569.apigatewayjava.quota.QuotaDecision;

/**
 * 接入控制结果。成功时携带已认证的调用方档案；失败时携带可区分的错误码与可解释原因。
 */
public interface AccessService {

    record AccessResult(boolean granted, ErrorCode errorCode, CallerProfile caller, String reason) {
        public static AccessResult ok(CallerProfile caller) {
            return new AccessResult(true, null, caller, "身份有效且配额内放行");
        }

        public static AccessResult reject(ErrorCode code, String reason) {
            return new AccessResult(false, code, null, reason);
        }
    }

    /**
     * 依次执行：身份认证 -> 资源授权 -> 单成本配额判定（成本=1）。
     * 前一步不通过则短路，保证三类拒绝
     * （IDENTITY_INVALID / IDENTITY_FORBIDDEN / QUOTA_EXCEEDED）互不混淆。
     */
    default AccessResult check(String callerId, String resource) {
        AccessResult auth = authenticate(callerId, resource);
        if (!auth.granted()) {
            return auth;
        }
        return charge(auth.caller(), resource, 1L);
    }

    /**
     * 仅做身份认证与资源授权，不消耗任何配额（也不占用在途名额）。
     * 供需要先知道「批量子请求条数」再决定配额成本的批量路径使用。
     */
    AccessResult authenticate(String callerId, String resource);

    /**
     * 对已认证调用方按成本进行配额判定：单请求 cost=1；批量 cost=子请求条数。
     * 返回结果中携带本次实际消耗、窗口已用量与剩余额度，供日志与调用方核对账目。
     */
    AccessResult charge(CallerProfile caller, String resource, long cost);

    /** 与 {@link #charge} 相同，但额外返回底层配额判定（含 used/remaining/cost）。 */
    QuotaDecision chargeDecision(CallerProfile caller, String resource, long cost);
}
