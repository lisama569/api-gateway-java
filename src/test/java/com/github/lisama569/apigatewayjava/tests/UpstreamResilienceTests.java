package com.github.lisama569.apigatewayjava.tests;

import com.github.lisama569.apigatewayjava.model.GatewayResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Timeout/partial failure normalization and retry safety.
 */
class UpstreamResilienceTests {

    private static final Logger log = LoggerFactory.getLogger("GATEWAY");

    private GatewayTestHarness harness;

    @BeforeEach
    void setUp() {
        harness = new GatewayTestHarness();
    }

    @Test
    void timeoutIsNormalizedTo504AndDegradedBody() {
        long start = System.currentTimeMillis();
        GatewayResponse r = harness.gateway().invoke(
                "orders", "v3", "key-premium", "timeout", null);
        long elapsed = System.currentTimeMillis() - start;

        log.info("[GATEWAY] request input: resource=orders version=v3 scenario=timeout -> code={} status={} attempts={} elapsedMs={}",
                r.code(), r.httpStatus(), r.attempts(), elapsed);

        assertThat(r.code()).isEqualTo("UPSTREAM_TIMEOUT");
        assertThat(r.httpStatus()).isEqualTo(504);
        assertThat(r.degraded()).isTrue();
        // idempotent timeout is retried once: bounded by 2 gateway deadlines + backoff,
        // far below the 2x400ms the upstream itself slept
        assertThat(r.attempts()).isEqualTo(2);
        assertThat(elapsed).isLessThan(600L);
    }

    @Test
    void partialFailureHasDistinctCodeAnd207() {
        GatewayResponse r = harness.gateway().invoke(
                "orders", "v3", "key-premium", "partial", null);
        log.info("[GATEWAY] upstream result: code={} status={} body={}",
                r.code(), r.httpStatus(), r.body());

        assertThat(r.code()).isEqualTo("UPSTREAM_PARTIAL_FAILURE");
        assertThat(r.httpStatus()).isEqualTo(207);
        assertThat(r.body()).contains("\"ok\":false");
    }

    @Test
    void idempotentFailureIsRetriedAndEventuallySucceedsWithSingleSideEffect() {
        GatewayResponse r = harness.gateway().invoke(
                "orders", "v3", "key-premium", "fail:1", null);
        log.info("[GATEWAY] upstream result: code={} attempts={} upstreamCalls={} sideEffects={} body={}",
                r.code(), r.attempts(), harness.upstream().invocationCount(),
                harness.upstream().sideEffectCount(), r.body());

        assertThat(r.success()).isTrue();
        assertThat(r.attempts()).isEqualTo(2);
        assertThat(harness.upstream().invocationCount()).isEqualTo(2);
        // first attempt failed without committing; only the retry committed
        assertThat(harness.upstream().sideEffectCount()).isEqualTo(1);
    }

    @Test
    void nonIdempotentFailureIsNeverRetried() {
        // payments v1 is marked non-idempotent
        GatewayResponse r = harness.gateway().invoke(
                "payments", "v1", "key-premium", "fail:1", null);
        log.info("[GATEWAY] upstream result: resource=payments code={} attempts={} upstreamCalls={} sideEffects={}",
                r.code(), r.attempts(), harness.upstream().invocationCount(),
                harness.upstream().sideEffectCount());

        assertThat(r.code()).isEqualTo("UPSTREAM_ERROR");
        assertThat(r.attempts()).isEqualTo(1);
        assertThat(harness.upstream().invocationCount()).isEqualTo(1);
        // a single failed dispatch -> no side effect, and crucially no duplicated retry
        assertThat(harness.upstream().sideEffectCount()).isZero();
    }

    @Test
    void nonRetryableExhaustedIdempotentCallDegradesAfterMaxAttempts() {
        // always errors; quota for premium is 20 so only one gateway call is made
        GatewayResponse r = harness.gateway().invoke(
                "orders", "v3", "key-premium", "error", null);
        assertThat(r.code()).isEqualTo("UPSTREAM_ERROR");
        assertThat(r.attempts()).isEqualTo(2);
        assertThat(r.degraded()).isTrue();
        assertThat(harness.upstream().invocationCount()).isEqualTo(2);
    }
}
