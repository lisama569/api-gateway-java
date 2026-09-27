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
 * 弹性上游客户端：在真实后端之上叠加「超时控制 + 重试 + 熔断 + 部分失败归一化」。
 *
 * 重试安全规则：仅对幂等请求（GET 等）重试；非幂等请求（POST 等）只尝试一次，
 * 因此上游在写操作过程中超时也不会造成重复业务副作用。熔断路径上的请求被快速
 * 失败，attemptCount=0，同样不会触达上游。
 *
 * 两类快速降级可区分（错误分类与日志一一对应）：
 * <ul>
 *   <li>OPEN 挡回：{@link UpstreamFailureKind#UPSTREAM_UNAVAILABLE}，上游仍在隔离期；</li>
 *   <li>HALF_OPEN 名额用尽：{@link UpstreamFailureKind#RECOVERY_LIMITED}，
 *       恢复期探测名额已被占用，可尽快重试。</li>
 * </ul>
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

        CircuitBreaker.Admission admission = circuitBreaker.admit(key);
        if (admission != CircuitBreaker.Admission.ALLOWED) {
            // 两类快速降级：均不触达上游（attemptCount=0、无副作用），但错误分类与日志可区分
            if (admission == CircuitBreaker.Admission.REJECTED_RECOVERY_LIMITED) {
                log.info("[上游结果] key={} 恢复期(HALF_OPEN)探测名额已用尽，快速失败（不触达上游）"
                        + " -> RECOVERY_LIMITED，可尽快重试", key);
                return UpstreamOutcome.failed(UpstreamFailureKind.RECOVERY_LIMITED, 0, true);
            }
            log.info("[上游结果] key={} 熔断打开(OPEN)，快速失败（不触达上游）"
                    + " -> UPSTREAM_UNAVAILABLE，应继续退避", key);
            return UpstreamOutcome.failed(UpstreamFailureKind.UPSTREAM_UNAVAILABLE, 0, true);
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
        // 批量调用不做整体重试，逐条执行：单条故障被隔离，最终归一化为
        // 全部成功 / 部分失败 / 整批失败 / 整体降级（未触达上游）四类。
        // 无论哪一类，结果都携带与子请求逐条对齐的 BatchItem，失败/降级逐条归因，
        // 整批降级也不会丢弃任何一条明细。
        List<BatchItem> items = new ArrayList<>(requests.size());
        int succeeded = 0;
        int failedUpstream = 0;
        int degradedOpen = 0;
        int degradedRecovery = 0;
        for (int i = 0; i < requests.size(); i++) {
            UpstreamRequest sub = requests.get(i);
            UpstreamOutcome outcome = call(sub);
            if (outcome.success()) {
                succeeded++;
                items.add(BatchItem.ok(outcome.response(), outcome.attemptCount()));
            } else if (outcome.degraded()) {
                if (outcome.failureKind() == UpstreamFailureKind.RECOVERY_LIMITED) {
                    degradedRecovery++;
                    items.add(BatchItem.failed(UpstreamFailureKind.RECOVERY_LIMITED,
                            "第 " + i + " 条子请求：恢复期(HALF_OPEN)探测名额已用尽，"
                                    + "未触达上游，可尽快重试", 0, true));
                } else {
                    degradedOpen++;
                    items.add(BatchItem.failed(UpstreamFailureKind.UPSTREAM_UNAVAILABLE,
                            "第 " + i + " 条子请求：熔断打开(OPEN)，未触达上游，应继续退避",
                            0, true));
                }
            } else {
                failedUpstream++;
                UpstreamFailureKind kind = outcome.failureKind() == null
                        ? UpstreamFailureKind.ERROR_5XX : outcome.failureKind();
                items.add(BatchItem.failed(kind,
                        "第 " + i + " 条子请求上游失败：" + kind
                                + "，真实尝试 " + outcome.attemptCount() + " 次",
                        outcome.attemptCount(), false));
            }
        }

        int degraded = degradedOpen + degradedRecovery;
        int failed = failedUpstream + degraded;
        if (failed == 0) {
            return UpstreamOutcome.batchOk(items);
        }
        if (degraded == failed && succeeded == 0) {
            // 全部子请求均被熔断器快速失败：整批降级，未触达上游（attemptCount=0）。
            // 全部条目都是恢复期名额用尽时，整批分类也保持 RECOVERY_LIMITED；
            // 只要存在 OPEN 挡回，整批分类为 UPSTREAM_UNAVAILABLE（保守退避）。
            UpstreamFailureKind batchKind = degradedOpen == 0
                    ? UpstreamFailureKind.RECOVERY_LIMITED
                    : UpstreamFailureKind.UPSTREAM_UNAVAILABLE;
            log.info("[上游结果] 批量调用整体降级（未触达上游）total={} open挡回={} 恢复期名额用尽={}",
                    requests.size(), degradedOpen, degradedRecovery);
            return UpstreamOutcome.batchDegraded(items, batchKind);
        }
        if (succeeded == 0) {
            // 整批失败：与部分失败严格区分，调用方应整批重试而非逐条重试
            log.info("[上游结果] 批量调用整批失败 total={} 上游失败={} 降级={}",
                    requests.size(), failedUpstream, degraded);
            return UpstreamOutcome.batchFailed(items);
        }
        log.info("[上游结果] 批量调用部分失败 succeeded={} 上游失败={} 降级={}",
                succeeded, failedUpstream, degraded);
        return UpstreamOutcome.partial(items, succeeded, failed);
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
