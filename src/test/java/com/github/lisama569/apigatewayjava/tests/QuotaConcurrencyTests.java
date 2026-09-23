package com.github.lisama569.apigatewayjava.tests;

import com.github.lisama569.apigatewayjava.model.QuotaDecision;
import com.github.lisama569.apigatewayjava.ratelimit.QuotaService;
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
 * Concurrent quota: the limit must never be exceeded and no admitted request must be missed.
 */
class QuotaConcurrencyTests {

    private static final Logger log = LoggerFactory.getLogger("GATEWAY");

    private final QuotaService quotaService = new QuotaService();

    @Test
    void concurrentRequestsNeverExceedOrMissQuota() throws Exception {
        String caller = "key-basic";
        long limit = 10;
        int threads = 50;
        int perThread = 4;

        quotaService.reset(caller);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger allowedCount = new AtomicInteger();
        AtomicInteger rejectedCount = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();

        for (int t = 0; t < threads; t++) {
            futures.add(pool.submit(() -> {
                start.await();
                for (int i = 0; i < perThread; i++) {
                    QuotaDecision d = quotaService.tryAcquire(caller, limit);
                    log.info("[GATEWAY] rate-limit decision: caller={} allowed={} used={}/{} reason={}",
                            caller, d.allowed(), d.used(), d.limit(), d.reason());
                    if (d.allowed()) {
                        allowedCount.incrementAndGet();
                    } else {
                        rejectedCount.incrementAndGet();
                    }
                }
                return null;
            }));
        }

        start.countDown();
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();

        int total = threads * perThread;
        log.info("[GATEWAY] request input: caller={} total={} limit={} allowed={} rejected={}",
                caller, total, limit, allowedCount.get(), rejectedCount.get());

        // exactly `limit` admitted - neither broken through nor under-counted
        assertThat(allowedCount.get()).isEqualTo(limit);
        assertThat(rejectedCount.get()).isEqualTo(total - limit);
        assertThat(allowedCount.get() + rejectedCount.get()).isEqualTo(total);

        // explainability of a rejection
        QuotaDecision rejection = quotaService.tryAcquire(caller, limit);
        assertThat(rejection.allowed()).isFalse();
        assertThat(rejection.used()).isEqualTo(limit);
        assertThat(rejection.reason()).contains("quota exhausted");
    }
}
