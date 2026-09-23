package com.github.lisama569.apigatewayjava.access;

import com.github.lisama569.apigatewayjava.core.error.ErrorCode;
import com.github.lisama569.apigatewayjava.identity.CallerProfile;

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
     * 依次执行：身份认证 -> 资源授权 -> 配额判定。前一步不通过则短路，
     * 保证三类拒绝（IDENTITY_INVALID / IDENTITY_FORBIDDEN / QUOTA_EXCEEDED）互不混淆。
     */
    AccessResult check(String callerId, String resource);
}
