package com.github.lisama569.apigatewayjava.tests;

import com.github.lisama569.apigatewayjava.model.GatewayResponse;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end concurrency: 60 simultaneous calls for a caller with quota 20.
 * Exactly 20 reach the upstream; the rest get a consistent QUOTA_EXCEEDED/429.
 */
class GatewayConcurrencyTests {

    private static final Logger log = LoggerFactory.getLogger("GATEWAY");

    @Test
    void quotaHoldsEndToEndUnderConcurrency() throws Exception {
        GatewayTestHarness harness = new GatewayTestHarness();
        int threads = 60;
        long quota = 20L;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger limited = new AtomicInteger();
        List<Future<GatewayResponse>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            final int n = i;
            futures.add(pool.submit(() -> {
                start.await();
                GatewayResponse r = harness.gateway().invoke(
                        "orders", "v3", "key-premium", "ok", "req-" + n);
                if (r.success()) {
                    ok.incrementAndGet();
                } else if ("QUOTA_EXCEEDED".equals(r.code())) {
                    limited.incrementAndGet();
                }
                return r;
            }));
        }
        start.countDown();

        List<GatewayResponse> responses = new ArrayList<>();
        for (Future<GatewayResponse> f : futures) {
            responses.add(f.get());
        }
        pool.shutdown();

        log.info("[GATEWAY] request input: concurrentCalls={} quota={} -> ok={} quotaExceeded={}",
                threads, quota, ok.get(), limited.get());

        assertThat(ok.get()).isEqualTo(quota);
        assertThat(limited.get()).isEqualTo(threads - quota);
        assertThat(ok.get() + limited.get()).isEqualTo(threads);

        // status-code consistency for each distinguishable class
        assertThat(responses.stream().filter(GatewayResponse::success))
                .allMatch(r -> r.httpStatus() == 200 && "OK".equals(r.code()));
        assertThat(responses.stream().filter(r -> "QUOTA_EXCEEDED".equals(r.code())))
                .allMatch(r -> r.httpStatus() == 429);

        // exactly one physical side effect per admitted call; none for rejected calls
        assertThat(harness.upstream().sideEffectCount()).isEqualTo(quota);
        assertThat(harness.upstream().invocationCount()).isEqualTo(quota);
    }
}
