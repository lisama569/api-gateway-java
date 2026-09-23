package com.github.lisama569.apigatewayjava.tests;

import com.github.lisama569.apigatewayjava.model.CircuitState;
import com.github.lisama569.apigatewayjava.model.GatewayResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Consecutive failures open the breaker; while open no upstream call (hence no business
 * side effect) may happen; after cooldown a probe succeeds and traffic returns to normal.
 */
class CircuitBreakerRecoveryTests {

    private static final Logger log = LoggerFactory.getLogger("GATEWAY");
    private static final String UPSTREAM = "orders-upstream";

    private GatewayTestHarness harness;

    @BeforeEach
    void setUp() {
        harness = new GatewayTestHarness();
    }

    @Test
    void opensAfterConsecutiveFailuresFailsFastThenRecovers() throws Exception {
        harness.upstream().setDown(true);

        // first 3 gateway calls hit the failing upstream (each: 1 attempt since deterministic
        // error is still retried once -> 2 physical calls) and trip the breaker
        GatewayResponse r1 = call();
        GatewayResponse r2 = call();
        GatewayResponse r3 = call();
        assertThat(r1.code()).isEqualTo("UPSTREAM_ERROR");
        assertThat(r2.code()).isEqualTo("UPSTREAM_ERROR");
        assertThat(r3.code()).isEqualTo("UPSTREAM_ERROR");
        assertThat(harness.breaker().state(UPSTREAM)).isEqualTo(CircuitState.OPEN);
        int physicalCallsAtOpen = harness.upstream().invocationCount();

        // while open: fast-fail with a distinct code, zero new physical upstream calls,
        // zero new business side effects
        GatewayResponse openResp = call();
        log.info("[GATEWAY] upstream result: while OPEN code={} status={} attempts={} degraded={}",
                openResp.code(), openResp.httpStatus(), openResp.attempts(), openResp.degraded());
        assertThat(openResp.code()).isEqualTo("UPSTREAM_CIRCUIT_OPEN");
        assertThat(openResp.httpStatus()).isEqualTo(503);
        assertThat(openResp.degraded()).isTrue();
        assertThat(openResp.attempts()).isZero();
        assertThat(harness.upstream().invocationCount()).isEqualTo(physicalCallsAtOpen);

        // wait past the cooldown; the next call is the single half-open probe
        Thread.sleep(1_100L);
        harness.upstream().setDown(false);

        GatewayResponse probe = call();
        log.info("[GATEWAY] upstream result: probe after cooldown code={} success={}",
                probe.code(), probe.success());
        assertThat(probe.success()).isTrue();
        assertThat(harness.breaker().state(UPSTREAM)).isEqualTo(CircuitState.CLOSED);

        // fully back on the normal path
        GatewayResponse normal = call();
        assertThat(normal.success()).isTrue();
        assertThat(normal.degraded()).isFalse();
        assertThat(normal.code()).isEqualTo("OK");
    }

    private GatewayResponse call() {
        GatewayResponse r = harness.gateway().invoke(
                "orders", "v3", "key-premium", "ok", null);
        log.info("[GATEWAY] request input: resource=orders version=v3 scenario=ok down={} -> code={} breaker={} upstreamCalls={}",
                harness.upstream().invocationCount(), r.code(),
                harness.breaker().state(UPSTREAM), harness.upstream().invocationCount());
        return r;
    }
}
