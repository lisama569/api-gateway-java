package com.github.lisama569.apigatewayjava.api;

import com.github.lisama569.apigatewayjava.access.AccessService;
import com.github.lisama569.apigatewayjava.concurrency.ConcurrencyLimiter;
import com.github.lisama569.apigatewayjava.core.error.ErrorCode;
import com.github.lisama569.apigatewayjava.identity.CallerProfile;
import com.github.lisama569.apigatewayjava.quota.QuotaDecision;
import com.github.lisama569.apigatewayjava.upstream.UpstreamClient;
import com.github.lisama569.apigatewayjava.upstream.UpstreamFailureKind;
import com.github.lisama569.apigatewayjava.upstream.UpstreamOutcome;
import com.github.lisama569.apigatewayjava.upstream.UpstreamRequest;
import com.github.lisama569.apigatewayjava.upstream.UpstreamResponse;
import com.github.lisama569.apigatewayjava.version.VersionedResource;
import com.github.lisama569.apigatewayjava.version.VersionRegistry;
import com.github.lisama569.apigatewayjava.version.VersionStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class GatewayService {

    private static final Logger log = LoggerFactory.getLogger(GatewayService.class);

    private final VersionRegistry versionRegistry;
    private final AccessService accessService;
    private final UpstreamClient upstreamClient;
    private final ConcurrencyLimiter concurrencyLimiter;

    @Autowired
    public GatewayService(VersionRegistry versionRegistry,
                          AccessService accessService,
                          UpstreamClient upstreamClient,
                          ConcurrencyLimiter concurrencyLimiter) {
        this.versionRegistry = versionRegistry;
        this.accessService = accessService;
        this.upstreamClient = upstreamClient;
        this.concurrencyLimiter = concurrencyLimiter;
    }

    /** 便捷构造器：使用默认在途并发限流器（上限较大，主要供历史单测使用）。 */
    public GatewayService(VersionRegistry versionRegistry,
                          AccessService accessService,
                          UpstreamClient upstreamClient) {
        this(versionRegistry, accessService, upstreamClient,
                new com.github.lisama569.apigatewayjava.concurrency.InFlightConcurrencyLimiter(
                        Integer.MAX_VALUE, false));
    }

    public GatewayResult route(GatewayRequest request, String callerId, boolean batch) {
        log.info("[请求输入] caller={} resource={} version={} method={} batch={} payload={}",
                callerId, request.resource(), request.version(), request.method(), batch,
                request.payload());

        // 1) 版本判定：未登记 -> 404 VERSION_UNKNOWN；已移除 -> 410 VERSION_REMOVED
        VersionedResource versioned = versionRegistry
                .resolve(request.resource(), request.version())
                .orElse(null);
        if (versioned == null) {
            log.info("[版本判定] resource={} version={} -> VERSION_UNKNOWN",
                    request.resource(), request.version());
            return reject(ErrorCode.VERSION_UNKNOWN,
                    "未知版本：资源 '" + request.resource() + "' 不存在版本 '"
                            + request.version() + "'");
        }
        if (versioned.status() == VersionStatus.REMOVED) {
            log.info("[版本判定] resource={} version={} -> VERSION_REMOVED",
                    request.resource(), request.version());
            return reject(ErrorCode.VERSION_REMOVED, versioned.removalReason());
        }

        // 2) 身份认证 + 资源授权：401 / 403（不消耗配额、不占在途名额）
        AccessService.AccessResult auth = accessService.authenticate(callerId, request.resource());
        if (!auth.granted()) {
            log.info("[限流判定] caller={} resource={} granted={} code={} reason={}",
                    callerId, request.resource(), false, auth.errorCode(), auth.reason());
            return reject(auth.errorCode(), auth.reason());
        }
        CallerProfile caller = auth.caller();

        // 3) 在途并发上限：超限快速返回 CALLER_OVERLOADED，不排队、不消耗配额
        if (!concurrencyLimiter.tryAcquire(caller.callerId(), request.resource())) {
            int inFlight = concurrencyLimiter.inFlight(caller.callerId(), request.resource());
            String reason = "调用方 '" + caller.callerId() + "' 在途并发已达上限（当前在途 "
                    + inFlight + "），请求被快速过载拒绝（未消耗配额），请稍后重试";
            log.info("[限流判定] caller={} resource={} granted=false code=CALLER_OVERLOADED 在途={} reason={}",
                    caller.callerId(), request.resource(), inFlight, reason);
            return reject(ErrorCode.CALLER_OVERLOADED, reason);
        }

        try {
            boolean idempotent = !"POST".equalsIgnoreCase(request.method())
                    && !"PATCH".equalsIgnoreCase(request.method())
                    && !"DELETE".equalsIgnoreCase(request.method());
            String key = request.resource() + ":" + request.version();

            if (batch) {
                return routeBatch(request, caller, key, idempotent, versioned);
            }

            // 4) 单请求配额：成本固定为 1（保持既有行为）
            QuotaDecision decision = accessService.chargeDecision(caller, request.resource(), 1L);
            log.info("[限流判定] caller={} resource={} granted={} code={} 本次消耗={} 已用={}/{} 剩余={} reason={}",
                    caller.callerId(), request.resource(), decision.allowed(),
                    decision.allowed() ? null : ErrorCode.QUOTA_EXCEEDED,
                    decision.cost(), decision.used(), decision.limit(), decision.remaining(),
                    decision.reason());
            if (!decision.allowed()) {
                return reject(ErrorCode.QUOTA_EXCEEDED, decision.reason());
            }

            UpstreamRequest upstreamRequest = new UpstreamRequest(key, request.method(),
                    request.resource(), request.version(), request.payload(), idempotent);
            UpstreamOutcome outcome = upstreamClient.call(upstreamRequest);
            log.info("[上游调用结果] key={} success={} kind={} attempts={} degraded={}",
                    key, outcome.success(), outcome.failureKind(), outcome.attemptCount(),
                    outcome.degraded());
            return toGatewayResult(outcome, versioned);
        } finally {
            // 无论成功/失败/降级，在途名额立即释放，压力回落后自动恢复
            concurrencyLimiter.release(caller.callerId(), request.resource());
        }
    }

    private GatewayResult routeBatch(GatewayRequest request, CallerProfile caller, String key,
                                     boolean idempotent, VersionedResource versioned) {
        // payload 为子请求条数（缺省 3，非法值回退 3）
        int subCount = parseSubCount(request.payload());

        // 批量配额：按子请求条数整体原子计费。额度不足覆盖整批 -> 整笔拒绝，
        // 计数器不变（账目不会只扣一部分），也不会触达任何上游（无重复副作用）。
        QuotaDecision decision =
                accessService.chargeDecision(caller, request.resource(), subCount);
        log.info("[限流判定] caller={} resource={} batch granted={} code={} 本次批量消耗={} 已用={}/{} 剩余={} reason={}",
                caller.callerId(), request.resource(), decision.allowed(),
                decision.allowed() ? null : ErrorCode.QUOTA_EXCEEDED,
                decision.cost(), decision.used(), decision.limit(), decision.remaining(),
                decision.reason());
        if (!decision.allowed()) {
            return reject(ErrorCode.QUOTA_EXCEEDED, decision.reason());
        }

        List<UpstreamRequest> subs = new ArrayList<>();
        for (int i = 0; i < subCount; i++) {
            subs.add(new UpstreamRequest(key, request.method(), request.resource(),
                    request.version(), "\"item-" + i + "\"", idempotent));
        }
        UpstreamOutcome outcome = upstreamClient.callBatch(subs);

        List<BatchItemView> views = new ArrayList<>();
        List<Object> rawData = new ArrayList<>();
        int succeeded = 0;
        int failed = 0;
        for (int i = 0; i < outcome.items().size(); i++) {
            UpstreamOutcome.Item item = outcome.items().get(i);
            if (item.success()) {
                succeeded++;
                views.add(new BatchItemView(i, true, 200, null, false, item.response().body()));
                rawData.add(item.response());
            } else {
                failed++;
                ErrorCode itemCode = mapFailureKind(item.failureKind());
                Object body = item.response() == null ? null : item.response().body();
                views.add(new BatchItemView(i, false, itemCode.httpStatus(), itemCode.name(),
                        item.degraded(), body));
                rawData.add(item.response());
            }
        }
        log.info("[上游调用结果] 批量 key={} total={} succeeded={} failed={} attempts={} degradedItems={}",
                key, subCount, succeeded, failed, outcome.attemptCount(),
                views.stream().filter(BatchItemView::degraded).count());

        boolean deprecated = versioned.status() == VersionStatus.DEPRECATED;
        BatchStatus status;
        boolean overallSuccess;
        ErrorCode code;
        int httpStatus;
        String message;
        if (failed == 0) {
            status = BatchStatus.ALL_SUCCESS;
            overallSuccess = true;
            code = null;
            httpStatus = 200;
            message = "批量全部成功（" + subCount + "/" + subCount + "）";
        } else if (succeeded > 0) {
            status = BatchStatus.PARTIAL_SUCCESS;
            overallSuccess = false;
            code = ErrorCode.UPSTREAM_PARTIAL_FAILURE;
            httpStatus = 207;
            message = "批量部分成功：" + succeeded + " 成功 / " + failed
                    + " 失败，成功项结果保留，失败项逐条可归因，可仅重试失败项";
        } else {
            status = BatchStatus.ALL_FAILED;
            overallSuccess = false;
            code = ErrorCode.UPSTREAM_BATCH_ALL_FAILED;
            httpStatus = ErrorCode.UPSTREAM_BATCH_ALL_FAILED.httpStatus();
            boolean allDegraded = views.stream().allMatch(BatchItemView::degraded);
            message = allDegraded
                    ? "批量整批失败：全部子请求被熔断/恢复期降级，均未触达上游（无重复副作用），建议稍后整批重试"
                    : "批量整批失败：" + failed + " 条子请求全部失败（非部分成功），建议整批重试";
        }

        GatewayResult.GatewayResultBuilder b = GatewayResult.builder()
                .success(overallSuccess)
                .httpStatus(httpStatus)
                .message(message)
                .batchStatus(status)
                .batchItems(views)
                .batchData(rawData)
                .batchCounts(subCount, succeeded, failed)
                .degraded(views.stream().anyMatch(BatchItemView::degraded))
                .deprecated(deprecated)
                .deprecationNotice(deprecated ? versioned.deprecationNotice() : null);
        if (code != null) {
            b.errorCode(code);
        }
        return b.build();
    }

    private int parseSubCount(String payload) {
        try {
            if (payload != null && !payload.isBlank()) {
                int parsed = Integer.parseInt(payload.trim());
                if (parsed >= 1) {
                    return parsed;
                }
            }
        } catch (NumberFormatException ignored) {
            // 回退默认值
        }
        return 3;
    }

    private ErrorCode mapFailureKind(UpstreamFailureKind kind) {
        if (kind == null) {
            return ErrorCode.UPSTREAM_ERROR;
        }
        return switch (kind) {
            case TIMEOUT -> ErrorCode.UPSTREAM_TIMEOUT;
            case PARTIAL_FAILURE -> ErrorCode.UPSTREAM_PARTIAL_FAILURE;
            case ERROR_5XX -> ErrorCode.UPSTREAM_ERROR;
            case BREAKER_OPEN -> ErrorCode.UPSTREAM_UNAVAILABLE;
            case RECOVERY_LIMITED -> ErrorCode.UPSTREAM_RECOVERY_LIMITED;
        };
    }

    private GatewayResult toGatewayResult(UpstreamOutcome outcome, VersionedResource versioned) {
        if (outcome.success()) {
            return buildOk(versioned, outcome.response());
        }
        if (outcome.degraded()) {
            ErrorCode code = outcome.failureKind() == UpstreamFailureKind.RECOVERY_LIMITED
                    ? ErrorCode.UPSTREAM_RECOVERY_LIMITED
                    : ErrorCode.UPSTREAM_UNAVAILABLE;
            String msg = code == ErrorCode.UPSTREAM_RECOVERY_LIMITED
                    ? "熔断恢复期探测名额已满，请求被快速降级（未触达上游，无重复副作用），探测成功后自动恢复"
                    : "上游熔断器打开，请求被快速降级（未触达上游，无重复副作用）";
            return GatewayResult.builder()
                    .success(false)
                    .errorCode(code)
                    .httpStatus(code.httpStatus())
                    .message(msg)
                    .degraded(true)
                    .build();
        }
        ErrorCode code = mapFailureKind(outcome.failureKind());
        String message = switch (code) {
            case UPSTREAM_TIMEOUT -> "上游调用超时，已按重试策略处理后仍失败";
            case UPSTREAM_ERROR -> "上游返回错误（5xx），已归一化为 UPSTREAM_ERROR";
            case UPSTREAM_PARTIAL_FAILURE -> "部分上游请求失败";
            default -> "上游调用失败";
        };
        return reject(code, message);
    }

    private GatewayResult buildOk(VersionedResource versioned, UpstreamResponse single) {
        boolean deprecated = versioned.status() == VersionStatus.DEPRECATED;
        return GatewayResult.builder()
                .success(true)
                .httpStatus(200)
                // 保持既有单请求信封形态：data 为完整上游响应对象（含 status/body）
                .data(single)
                .deprecated(deprecated)
                .deprecationNotice(deprecated ? versioned.deprecationNotice() : null)
                .degraded(false)
                .message(deprecated
                        ? "调用成功，但该版本已废弃：" + versioned.deprecationNotice()
                        : "调用成功")
                .build();
    }

    private GatewayResult reject(ErrorCode code, String message) {
        return GatewayResult.builder()
                .success(false)
                .errorCode(code)
                .httpStatus(code.httpStatus())
                .message(message)
                .build();
    }
}
