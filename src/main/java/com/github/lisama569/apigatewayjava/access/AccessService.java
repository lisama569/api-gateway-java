package com.github.lisama569.apigatewayjava.access;

import com.github.lisama569.apigatewayjava.core.error.ErrorCode;
import com.github.lisama569.apigatewayjava.identity.CallerProfile;
import com.github.lisama569.apigatewayjava.quota.QuotaDecision;

/**
 * 接入控制结果。成功时携带已认证的调用方档案与配额判定明细；
 * 失败时携带可区分的错误码与可解释原因。
 */
public interface AccessService {

    record AccessResult(boolean granted, ErrorCode errorCode, CallerProfile caller,
                        QuotaDecision quota, String reason) {
        public static AccessResult ok(CallerProfile caller, QuotaDecision quota) {
            return new AccessResult(true, null, caller, quota, "身份有效且配额内放行");
        }

        public static AccessResult reject(ErrorCode code, String reason) {
            return new AccessResult(false, code, null, null, reason);
        }
    }

    /**
     * 依次执行：身份认证 -> 资源授权 -> 配额判定。前一步不通过则短路，
     * 保证三类拒绝（IDENTITY_INVALID / IDENTITY_FORBIDDEN / QUOTA_EXCEEDED）互不混淆。
     */
    default AccessResult check(String callerId, String resource) {
        return check(callerId, resource, 1);
    }

    /**
     * 带配额成本的判定：批量调用按子请求数一次性扣减，
     * 额度不足时整批拒绝且账目不变（不会只扣一部分）。
     */
    AccessResult check(String callerId, String resource, long cost);
}
