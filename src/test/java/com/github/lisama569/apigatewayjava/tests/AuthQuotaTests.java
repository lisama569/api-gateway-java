package com.github.lisama569.apigatewayjava.tests;

import com.github.lisama569.apigatewayjava.model.GatewayResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Authentication, authorization and quota rejection reasons must be distinct and
 * consistent in their HTTP statuses.
 */
class AuthQuotaTests {

    private static final Logger log = LoggerFactory.getLogger("GATEWAY");

    private GatewayTestHarness harness;

    @BeforeEach
    void setUp() {
        harness = new GatewayTestHarness();
    }

    @Test
    void missingKeyIsInvalidIdentity() {
        GatewayResponse r = harness.gateway().invoke("orders", "v3", null, null, "x");
        log.info("[GATEWAY] request input: resource=orders version=v3 apiKey=<none> -> code={} status={}",
                r.code(), r.httpStatus());
        assertThat(r.code()).isEqualTo("AUTH_INVALID_IDENTITY");
        assertThat(r.httpStatus()).isEqualTo(401);
    }

    @Test
    void unknownKeyIsInvalidIdentity() {
        GatewayResponse r = harness.gateway().invoke("orders", "v3", "nope", null, "x");
        assertThat(r.code()).isEqualTo("AUTH_INVALID_IDENTITY");
        assertThat(r.httpStatus()).isEqualTo(401);
    }

    @Test
    void knownCallerWithoutPermissionIsForbidden() {
        GatewayResponse r = harness.gateway().invoke("payments", "v1", "key-basic", null, "x");
        log.info("[GATEWAY] request input: resource=payments version=v1 apiKey=key-basic -> code={} status={} explain={}",
                r.code(), r.httpStatus(), r.explain());
        assertThat(r.code()).isEqualTo("AUTH_FORBIDDEN");
        assertThat(r.httpStatus()).isEqualTo(403);
    }

    @Test
    void rejectionCodesAreDistinguishable() {
        GatewayResponse invalid = harness.gateway().invoke("orders", "v3", "nope", null, "x");
        GatewayResponse forbidden = harness.gateway().invoke("payments", "v1", "key-basic", null, "x");
        GatewayResponse removed = harness.gateway().invoke("orders", "v1", "key-premium", null, "x");
        assertThat(invalid.code()).isNotEqualTo(forbidden.code());
        assertThat(forbidden.code()).isNotEqualTo(removed.code());
    }

    @Test
    void quotaExceededIsReportedDistinctlyWithExplanation() {
        // key-basic has quota 3
        GatewayResponse first = harness.gateway().invoke("orders", "v3", "key-basic", null, "1");
        GatewayResponse second = harness.gateway().invoke("orders", "v3", "key-basic", null, "2");
        GatewayResponse third = harness.gateway().invoke("orders", "v3", "key-basic", null, "3");
        GatewayResponse fourth = harness.gateway().invoke("orders", "v3", "key-basic", null, "4");

        log.info("[GATEWAY] request input: 4 sequential calls key-basic(quota=3) -> codes={},{},{},{}",
                first.code(), second.code(), third.code(), fourth.code());

        assertThat(first.success()).isTrue();
        assertThat(second.success()).isTrue();
        assertThat(third.success()).isTrue();
        assertThat(fourth.code()).isEqualTo("QUOTA_EXCEEDED");
        assertThat(fourth.httpStatus()).isEqualTo(429);
        assertThat(String.valueOf(fourth.explain().get("quota"))).contains("used=3");
    }

    @Test
    void zeroQuotaCallerIsRejectedOnFirstRequest() {
        GatewayResponse r = harness.gateway().invoke("orders", "v3", "key-zero", null, "x");
        assertThat(r.code()).isEqualTo("QUOTA_EXCEEDED");
        assertThat(r.httpStatus()).isEqualTo(429);
    }
}
