package com.github.lisama569.apigatewayjava.tests;

import com.github.lisama569.apigatewayjava.model.GatewayResponse;
import com.github.lisama569.apigatewayjava.model.VersionOutcome;
import com.github.lisama569.apigatewayjava.model.VersionResolution;
import com.github.lisama569.apigatewayjava.version.VersionRegistry;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Version coexistence, deprecation hinting and distinguishable rejection reasons.
 */
class VersionRoutingTests {

    private static final Logger log = LoggerFactory.getLogger("GATEWAY");

    private final VersionRegistry registry = new VersionRegistry();
    private final GatewayTestHarness harness = new GatewayTestHarness();

    @Test
    void unknownAndRemovedVersionsAreDistinguishable() {
        VersionResolution unknown = registry.resolve("orders", "v99");
        VersionResolution removed = registry.resolve("orders", "v1");
        log.info("[GATEWAY] request input: resource=orders version=v99 -> outcome={} msg={}",
                unknown.outcome(), unknown.message());
        log.info("[GATEWAY] request input: resource=orders version=v1  -> outcome={} msg={}",
                removed.outcome(), removed.message());

        assertThat(unknown.outcome()).isEqualTo(VersionOutcome.UNKNOWN_VERSION);
        assertThat(removed.outcome()).isEqualTo(VersionOutcome.VERSION_REMOVED);
        assertThat(unknown.outcome()).isNotEqualTo(removed.outcome());
    }

    @Test
    void unknownResourceIsRejectedAsUnknownVersion() {
        VersionResolution resolution = registry.resolve("ghosts", "v1");
        assertThat(resolution.outcome()).isEqualTo(VersionOutcome.UNKNOWN_VERSION);
    }

    @Test
    void deprecatedVersionStillServedWithNotice() {
        GatewayResponse response = harness.gateway().invoke(
                "orders", "v2", "key-premium", null, "hello");
        log.info("[GATEWAY] request input: resource=orders version=v2 apiKey=key-premium -> code={} notices={}",
                response.code(), response.notices());

        assertThat(response.success()).isTrue();
        assertThat(response.httpStatus()).isEqualTo(200);
        assertThat(response.code()).isEqualTo("OK");
        assertThat(response.notices())
                .anyMatch(n -> n.contains("DEPRECATED"))
                .as("deprecated version must carry a recognizable deprecation hint");
    }

    @Test
    void removedVersionRejectedWith410AndDistinctCode() {
        GatewayResponse response = harness.gateway().invoke(
                "orders", "v1", "key-premium", null, "hello");
        assertThat(response.success()).isFalse();
        assertThat(response.httpStatus()).isEqualTo(410);
        assertThat(response.code()).isEqualTo("VERSION_REMOVED");
    }

    @Test
    void unknownVersionRejectedWith404AndDistinctCode() {
        GatewayResponse response = harness.gateway().invoke(
                "orders", "v42", "key-premium", null, "hello");
        assertThat(response.success()).isFalse();
        assertThat(response.httpStatus()).isEqualTo(404);
        assertThat(response.code()).isEqualTo("VERSION_UNKNOWN");
        assertThat(response.code()).isNotEqualTo("VERSION_REMOVED");
    }
}
