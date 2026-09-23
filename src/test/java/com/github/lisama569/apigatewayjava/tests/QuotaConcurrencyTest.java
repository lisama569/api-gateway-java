package com.github.lisama569.apigatewayjava.tests;

import com.github.lisama569.apigatewayjava.quota.FixedWindowQuotaService;
import com.github.lisama569.apigatewayjava.quota.QuotaDecision;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发压力下的配额测试：并发请求数远大于配额，验证不会突破也不会漏计。
 */
class QuotaConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(QuotaConcurrencyTest.class);

    @Test
    void quotaIsNeverBreachedUnderConcurrency() throws InterruptedException {
        long limit = 50;
        int threads = 200;
        FixedWindowQuotaService quota = new FixedWindowQuotaService(60_000L, () -> 0L);

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(20);
        AtomicInteger granted = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        List<QuotaDecision> decisions = java.util.Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threads; i++) {
            final String caller = (i % 2 == 0) ? "caller-x" : "caller-y";
            pool.submit(() -> {
                try {
                    start.await();
                    QuotaDecision d = quota.tryAcquire(caller, limit);
                    decisions.add(d);
                    if (d.allowed()) {
                        granted.incrementAndGet();
                    } else {
                        rejected.incrementAndGet();
                    }
                    log.info("[限流判定] caller={} input=1req allowed={} used={}/{} reason={}",
                            caller, d.allowed(), d.used(), d.limit(), d.reason());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS), "所有并发请求应在超时前完成");
        pool.shutdown();

        // 两个调用方各自 50 的配额 -> 恰好 100 个通过，其余拒绝
        assertEquals(100, granted.get(), "并发下放行数量必须恰好等于总配额，不得突破/漏计");
        assertEquals(threads - 100, rejected.get());
        decisions.stream().filter(QuotaDecision::allowed)
                .forEach(d -> assertEquals(limit, d.limit()));
        log.info("[并发配额结果] threads={} granted={} rejected={} limitPerCaller={}",
                threads, granted.get(), rejected.get(), limit);
    }

    @Test
    void quotaWindowRollsOver() {
        long[] clock = {0L};
        FixedWindowQuotaService quota = new FixedWindowQuotaService(1000L, () -> clock[0]);
        for (int i = 0; i < 3; i++) {
            assertTrue(quota.tryAcquire("z", 3).allowed());
        }
        assertEquals(false, quota.tryAcquire("z", 3).allowed());
        // 推进时间超过窗口长度 -> 新窗口重新获得配额
        clock[0] = 1001L;
        assertTrue(quota.tryAcquire("z", 3).allowed());
        log.info("[窗口翻转] 新窗口配额恢复正常");
    }
}
