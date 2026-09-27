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
 * 因此上游在写操作过程中超时也不会造成重复业务副作用。熔断打开时请求被快速失败，
 * attemptCount=0，同样不会触达上游。
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

        CircuitDecision decision = circuitBreaker.tryEnter(key);
        if (decision != CircuitDecision.ALLOWED) {
            // 两种快速失败必须可区分：OPEN 隔离期拒绝 vs HALF_OPEN 恢复期名额耗尽
            if (decision == CircuitDecision.REJECTED_HALF_OPEN) {
                log.info("[上游结果] key={} 半开恢复期探测名额已满，快速失败（不触达上游）kind={}",
                        key, UpstreamFailureKind.HALF_OPEN_PROBE_LIMIT);
                return UpstreamOutcome.failed(UpstreamFailureKind.HALF_OPEN_PROBE_LIMIT, 0, true);
            }
            log.info("[上游结果] key={} 熔断打开（隔离期），快速失败（不触达上游）kind={}",
                    key, UpstreamFailureKind.CIRCUIT_OPEN);
            return UpstreamOutcome.failed(UpstreamFailureKind.CIRCUIT_OPEN, 0, true);
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
        // 全部成功 / 部分失败 / 整批失败 / 整体降级（熔断打开，未触达上游）四类
        List<UpstreamResponse> responses = new ArrayList<>();
        int succeeded = 0;
        int failed = 0;
        int degraded = 0;
        boolean anyProbeLimited = false;
        for (UpstreamRequest sub : requests) {
            UpstreamOutcome outcome = call(sub);
            if (outcome.success()) {
                succeeded++;
                responses.add(outcome.response());
            } else {
                failed++;
                if (outcome.degraded()) {
                    degraded++;
                    if (outcome.failureKind() == UpstreamFailureKind.HALF_OPEN_PROBE_LIMIT) {
                        anyProbeLimited = true;
                    }
                }
                responses.add(new UpstreamResponse(500,
                        "{\"error\":\"" + (outcome.failureKind() == null
                                ? "UPSTREAM_UNAVAILABLE" : outcome.failureKind()) + "\"}"));
            }
        }
        if (failed == 0) {
            return UpstreamOutcome.batchOk(responses, succeeded);
        }
        if (degraded == failed && succeeded == 0) {
            // 全部子请求均被熔断器快速失败：整批视为降级，未触达上游（attemptCount=0）。
            // 逐条占位结果保留在 responses 中，调用方可逐条对上、逐条归因；
            // 降级原因（OPEN 隔离 / HALF_OPEN 名额耗尽）在 kind 上严格区分。
            UpstreamFailureKind kind = anyProbeLimited
                    ? UpstreamFailureKind.HALF_OPEN_PROBE_LIMIT
                    : UpstreamFailureKind.CIRCUIT_OPEN;
            log.info("[上游结果] 批量调用整体降级 kind={}（未触达上游）total={}", kind, requests.size());
            return UpstreamOutcome.batchDegraded(responses, kind);
        }
        if (succeeded == 0) {
            // 整批失败：与部分失败严格区分，调用方应整批重试而非逐条重试
            log.info("[上游结果] 批量调用整批失败 total={} failed={}", requests.size(), failed);
            return UpstreamOutcome.batchFailed(responses, failed);
        }
        log.info("[上游结果] 批量调用部分失败 succeeded={} failed={}", succeeded, failed);
        return UpstreamOutcome.partial(responses, succeeded, failed);
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
