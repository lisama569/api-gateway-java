package com.github.lisama569.apigatewayjava.tests;

import com.github.lisama569.apigatewayjava.quota.FixedWindowQuotaService;
import com.github.lisama569.apigatewayjava.quota.QuotaDecision;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 批量调用按子请求数计费的配额测试：
 * 并发下放行的子请求总量不突破额度、不漏计；
 * 额度不足时整批拒绝且账目不变（不存在只扣一部分）。
 */
class BatchQuotaTest {

    private static final Logger log = LoggerFactory.getLogger(BatchQuotaTest.class);

    @Test
    void batchCostIsChargedAtomicallyUnderConcurrency() throws InterruptedException {
        long limit = 50;
        long batchCost = 3;
        int threads = 60; // 60 批 * 3 = 180 个子请求，远超 50 额度
        FixedWindowQuotaService quota = new FixedWindowQuotaService(60_000L, () -> 0L);

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(20);
        AtomicLong grantedSubRequests = new AtomicLong();
        List<QuotaDecision> decisions = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    QuotaDecision d = quota.tryAcquire("caller-batch", limit, batchCost);
                    decisions.add(d);
                    if (d.allowed()) {
                        grantedSubRequests.addAndGet(d.consumed());
                    }
                    log.info("[限流判定] caller=caller-batch 批量成本={} allowed={} 本次消耗={} 已用={}/{} 剩余={}",
                            batchCost, d.allowed(), d.consumed(), d.used(), d.limit(), d.remaining());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS), "所有并发批量请求应在超时前完成");
        pool.shutdown();

        // 不突破：放行的子请求总量不超过额度
        assertTrue(grantedSubRequests.get() <= limit,
                "放行的子请求总量不得突破额度：" + grantedSubRequests.get());
        // 整批原子性：每个放行的批次都恰好扣了 batchCost，不存在部分扣减
        decisions.stream().filter(QuotaDecision::allowed)
                .forEach(d -> assertEquals(batchCost, d.consumed(),
                        "放行的批次必须整批扣减，不得只扣一部分"));
        decisions.stream().filter(d -> !d.allowed())
                .forEach(d -> assertEquals(0, d.consumed(),
                        "被拒绝的批次不得扣减任何额度"));
        // 不漏计：账目上的已用量 = 实际放行的子请求总量
        QuotaDecision probe = quota.tryAcquire("caller-batch", limit, 1);
        long recordedUsed = decisions.stream().mapToLong(QuotaDecision::used).max().orElse(0);
        long expectedUsed = grantedSubRequests.get() + (probe.allowed() ? 1 : 0);
        assertEquals(expectedUsed, Math.max(recordedUsed, probe.used()),
                "每个放行的子请求都必须计入账目");
        log.info("[批量配额结果] threads={} 放行子请求={}/{} 账目一致", threads, grantedSubRequests.get(), limit);
    }

    @Test
    void insufficientQuotaRejectsWholeBatchWithoutPartialDeduction() {
        FixedWindowQuotaService quota = new FixedWindowQuotaService(60_000L, () -> 0L);

        // 额度 5：第一批 3 个 -> 放行，已用 3 剩 2
        QuotaDecision first = quota.tryAcquire("caller-b2", 5, 3);
        assertTrue(first.allowed());
        assertEquals(3, first.consumed());
        assertEquals(3, first.used());
        assertEquals(2, first.remaining());
        log.info("[限流判定] 第一批 consumed={} used={}/{} 剩余={}",
                first.consumed(), first.used(), first.limit(), first.remaining());

        // 第二批 3 个 -> 剩余 2 不足，整批拒绝且一分不扣
        QuotaDecision second = quota.tryAcquire("caller-b2", 5, 3);
        assertFalse(second.allowed());
        assertEquals(0, second.consumed(), "拒绝时不得扣减任何额度");
        assertEquals(3, second.used(), "账目必须保持拒绝前的状态");
        assertTrue(second.reason().contains("未扣减"), "拒绝原因必须可解释：" + second.reason());
        log.info("[限流判定] 第二批额度不足 -> {} used={}/{}", second.reason(), second.used(), second.limit());

        // 第三批 2 个 -> 恰好等于剩余额度，放行且账目正好用满
        QuotaDecision third = quota.tryAcquire("caller-b2", 5, 2);
        assertTrue(third.allowed());
        assertEquals(5, third.used());
        assertEquals(0, third.remaining());
        log.info("[限流判定] 第三批 consumed={} used={}/{} 账目恰好用满，无错乱",
                third.consumed(), third.used(), third.limit());
    }
}
