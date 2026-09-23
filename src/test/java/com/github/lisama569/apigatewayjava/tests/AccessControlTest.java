package com.github.lisama569.apigatewayjava.tests;

import com.github.lisama569.apigatewayjava.access.DefaultAccessService;
import com.github.lisama569.apigatewayjava.access.AccessService;
import com.github.lisama569.apigatewayjava.core.error.ErrorCode;
import com.github.lisama569.apigatewayjava.identity.InMemoryCallerDirectory;
import com.github.lisama569.apigatewayjava.quota.FixedWindowQuotaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 身份认证 / 授权 / 配额三类拒绝必须映射为可区分结果。
 */
class AccessControlTest {

    private static final Logger log = LoggerFactory.getLogger(AccessControlTest.class);

    private DefaultAccessService access;

    @BeforeEach
    void setUp() {
        InMemoryCallerDirectory callers = new InMemoryCallerDirectory();
        callers.register("rich", java.util.Set.of("*"), 100);
        callers.register("orders-only", java.util.Set.of("orders"), 100);
        callers.register("poor", java.util.Set.of("*"), 2);
        FixedWindowQuotaService quota = new FixedWindowQuotaService(60_000L, () -> 0L);
        access = new DefaultAccessService(callers, quota);
    }

    @Test
    void missingIdentityIs401() {
        AccessService.AccessResult r = access.check(null, "orders");
        assertFalse(r.granted());
        assertEquals(ErrorCode.IDENTITY_INVALID, r.errorCode());
        assertEquals(401, r.errorCode().httpStatus());
        log.info("[限流判定] input=<no identity> -> {} reason={}", r.errorCode(), r.reason());
    }

    @Test
    void unknownIdentityIs401() {
        AccessService.AccessResult r = access.check("ghost", "orders");
        assertEquals(ErrorCode.IDENTITY_INVALID, r.errorCode());
        log.info("[限流判定] input=caller:ghost -> {} reason={}", r.errorCode(), r.reason());
    }

    @Test
    void forbiddenResourceIs403() {
        AccessService.AccessResult r = access.check("orders-only", "payments");
        assertFalse(r.granted());
        assertEquals(ErrorCode.IDENTITY_FORBIDDEN, r.errorCode());
        assertEquals(403, r.errorCode().httpStatus());
        log.info("[限流判定] input=orders-only@payments -> {} reason={}", r.errorCode(), r.reason());
    }

    @Test
    void quotaExceededIs429AndDistinguishable() {
        assertTrue(access.check("poor", "orders").granted());
        assertTrue(access.check("poor", "orders").granted());
        AccessService.AccessResult third = access.check("poor", "orders");
        assertFalse(third.granted());
        assertEquals(ErrorCode.QUOTA_EXCEEDED, third.errorCode());
        assertEquals(429, third.errorCode().httpStatus());
        assertTrue(third.reason().contains("2/2"), "判定依据必须可解释：" + third.reason());
        log.info("[限流判定] input=poor#3 -> {} reason={}", third.errorCode(), third.reason());
    }
}
