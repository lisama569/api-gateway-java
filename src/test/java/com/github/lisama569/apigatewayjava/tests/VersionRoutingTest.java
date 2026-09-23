package com.github.lisama569.apigatewayjava.tests;

import com.github.lisama569.apigatewayjava.access.DefaultAccessService;
import com.github.lisama569.apigatewayjava.api.GatewayRequest;
import com.github.lisama569.apigatewayjava.api.GatewayResult;
import com.github.lisama569.apigatewayjava.api.GatewayService;
import com.github.lisama569.apigatewayjava.core.error.ErrorCode;
import com.github.lisama569.apigatewayjava.identity.InMemoryCallerDirectory;
import com.github.lisama569.apigatewayjava.quota.FixedWindowQuotaService;
import com.github.lisama569.apigatewayjava.upstream.InMemoryBackend;
import com.github.lisama569.apigatewayjava.upstream.ResilientUpstreamClient;
import com.github.lisama569.apigatewayjava.upstream.SlidingWindowCircuitBreaker;
import com.github.lisama569.apigatewayjava.version.InMemoryVersionRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 版本并存、渐进式下线规则测试。
 */
class VersionRoutingTest {

    private static final Logger log = LoggerFactory.getLogger(VersionRoutingTest.class);

    private GatewayService gateway;
    private InMemoryBackend backend;

    @BeforeEach
    void setUp() {
        InMemoryVersionRegistry versions = new InMemoryVersionRegistry();
        InMemoryCallerDirectory callers = new InMemoryCallerDirectory();
        FixedWindowQuotaService quota = new FixedWindowQuotaService(60_000L, () -> 0L);
        DefaultAccessService access = new DefaultAccessService(callers, quota);
        backend = new InMemoryBackend();
        SlidingWindowCircuitBreaker breaker = new SlidingWindowCircuitBreaker(3, 5000L, () -> 0L);
        ResilientUpstreamClient upstream = new ResilientUpstreamClient(backend, breaker, 2, 300L);
        gateway = new GatewayService(versions, access, upstream);
    }

    @Test
    void multipleVersionsCoexist() {
        GatewayResult v1 = gateway.route(new GatewayRequest("orders", "v1", "GET", null),
                "caller-a", false);
        GatewayResult v2 = gateway.route(new GatewayRequest("orders", "v2", "GET", null),
                "caller-a", false);
        assertTrue(v1.isSuccess());
        assertTrue(v2.isSuccess());
        assertEquals(200, v1.getHttpStatus());
        log.info("[版本并存] orders v1 与 v2 均正常返回");
    }

    @Test
    void unknownVersionIsRejectedWith404() {
        GatewayResult r = gateway.route(new GatewayRequest("orders", "v99", "GET", null),
                "caller-a", false);
        assertFalse(r.isSuccess());
        assertEquals(ErrorCode.VERSION_UNKNOWN, r.getErrorCode());
        assertEquals(404, r.getHttpStatus());
        log.info("[版本拒绝] 未知版本 -> {} {}", r.getErrorCode(), r.getHttpStatus());
    }

    @Test
    void removedVersionIsRejectedWith410AndDistinguishable() {
        GatewayResult removed = gateway.route(new GatewayRequest("orders", "v-beta", "GET", null),
                "caller-a", false);
        GatewayResult unknown = gateway.route(new GatewayRequest("orders", "v99", "GET", null),
                "caller-a", false);
        assertFalse(removed.isSuccess());
        assertEquals(ErrorCode.VERSION_REMOVED, removed.getErrorCode());
        assertEquals(410, removed.getHttpStatus());
        assertNotNull(removed.getMessage());
        // 两种拒绝原因必须可区分
        assertNotSameCode(removed.getErrorCode(), unknown.getErrorCode());
        log.info("[版本拒绝] 已移除 -> {} (410)；未知 -> {} (404)",
                removed.getErrorCode(), unknown.getErrorCode());
    }

    @Test
    void deprecatedVersionStillServesWithNotice() {
        GatewayResult r = gateway.route(new GatewayRequest("orders", "v0", "GET", null),
                "caller-a", false);
        assertTrue(r.isSuccess());
        assertTrue(r.isDeprecated());
        assertNotNull(r.getDeprecationNotice());
        assertTrue(r.getDeprecationNotice().contains("v2"));
        log.info("[废弃版本] 正常返回且携带废弃提示：{}", r.getDeprecationNotice());
    }

    private static void assertNotSameCode(ErrorCode a, ErrorCode b) {
        org.junit.jupiter.api.Assertions.assertNotEquals(a, b);
    }
}
