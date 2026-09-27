package com.github.lisama569.apigatewayjava.upstream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 弹性上游客户端：在真实后端之上叠加「超时控制 + 重试 + 熔断 + 恢复期探测限流 + 批量归一化」。
 *
 * 重试安全规则：仅对幂等请求（GET 等）重试；非幂等请求（POST 等）只尝试一次，
 * 因此上游在写操作过程中超时也不会造成重复业务副作用。
 *
 * 熔断/恢复期规则：熔断打开期间请求被快速失败（{@link UpstreamFailureKind#BREAKER_OPEN}，
 * attemptCount=0，不触达上游）；HALF_OPEN 恢复期内只有熔断器放出的有限探测名额会触达上游，
 * 其余请求快速降级为 {@link UpstreamFailureKind#RECOVERY_LIMITED}（同样 attemptCount=0）。
 * 探测成功自动恢复正常放行，探测失败重新打开保持隔离，整个过程不产生重复副作用。
 */
@Component
public class ResilientUpstreamClient implements UpstreamClient {

    private static final Logger log = LoggerFactory.getLogger(ResilientUpstreamClient.class);

    private final InMemoryBackend backend;
    private final CircuitBreaker circuitBreaker;
    private final int maxAttempts;
    private final long timeoutMillis;
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "upstream-invoke");
        t.setDaemon(true);
        return t;
    });

    @Autowired
    public ResilientUpstreamClient(
            InMemoryBackend backend,
            CircuitBreaker circuitBreaker,
            @Value("${gateway.upstream.max-attempts:2}") int maxAttempts,
            @Value("${gateway.upstream.timeout-millis:300}") long timeoutMillis) {
        this.backend = backend;
        this.circuitBreaker = circuitBreaker;
        this.maxAttempts = maxAttempts;
        this.timeoutMillis = timeoutMillis;
    }

    @Override
    public UpstreamOutcome call(UpstreamRequest request) {
        String key = request.key();

        CircuitBreaker.Admission admission = circuitBreaker.allowRequest(key);
        if (admission == CircuitBreaker.Admission.REJECTED_OPEN) {
            log.info("[上游结果] key={} 熔断打开，快速失败（不触达上游）", key);
            return UpstreamOutcome.failed(UpstreamFailureKind.BREAKER_OPEN, 0, true);
        }
        if (admission == CircuitBreaker.Admission.REJECTED_RECOVERY_LIMITED) {
            log.info("[上游结果] key={} 恢复期探测名额已满，快速降级（不触达上游）", key);
            return UpstreamOutcome.failed(UpstreamFailureKind.RECOVERY_LIMITED, 0, true);
        }

        int attempts = Math.min(maxAttempts, request.idempotent() ? maxAttempts : 1);
        UpstreamException last = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                UpstreamResponse response = invokeWithTimeout(request);
                circuitBreaker.recordSuccess(key);
                log.info("[上游结果] key={} 成功 attempt={}/{} 触达次数={}",
                        key, attempt, attempts, attempt);
                return UpstreamOutcome.ok(response, attempt);
            } catch (UpstreamException e) {
                last = e;
                log.info("[上游结果] key={} 失败 kind={} attempt={}/{}",
                        key, e.getKind(), attempt, attempts);
            }
        }
        circuitBreaker.recordFailure(key);
        return UpstreamOutcome.failed(last == null ? UpstreamFailureKind.ERROR_5XX : last.getKind(),
                attempts, false);
    }

    @Override
    public UpstreamOutcome callBatch(List<UpstreamRequest> requests) {
        // 批量调用不做整体重试（避免整批重复业务副作用）：逐条执行，单条故障被隔离，
        // 最终由 UpstreamOutcome.batch 归一化为 全成功 / 部分成功 / 全失败 三种可区分状态。
        List<UpstreamOutcome.Item> items = new ArrayList<>();
        int succeeded = 0;
        int failed = 0;
        for (UpstreamRequest sub : requests) {
            UpstreamOutcome outcome = call(sub);
            if (outcome.success()) {
                succeeded++;
                items.add(UpstreamOutcome.Item.ok(outcome.response(), outcome.attemptCount()));
            } else {
                failed++;
                items.add(UpstreamOutcome.Item.failed(
                        outcome.failureKind(), outcome.attemptCount(), outcome.degraded()));
            }
        }
        UpstreamOutcome batch = UpstreamOutcome.batch(items);
        if (failed > 0) {
            log.info("[上游结果] 批量调用 succeeded={} failed={} total={} degradedItems={}",
                    succeeded, failed, requests.size(),
                    items.stream().filter(UpstreamOutcome.Item::degraded).count());
        }
        return batch;
    }

    private UpstreamResponse invokeWithTimeout(UpstreamRequest request) {
        Future<UpstreamResponse> future = executor.submit(() -> backend.invoke(request));
        try {
            return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new UpstreamException(UpstreamFailureKind.TIMEOUT,
                    "上游调用超时（>" + timeoutMillis + "ms）key=" + request.key());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UpstreamException(UpstreamFailureKind.TIMEOUT, "上游调用被中断");
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof UpstreamException ue) {
                throw ue;
            }
            throw new UpstreamException(UpstreamFailureKind.ERROR_5XX,
                    "上游执行异常：" + cause);
        }
    }
}
