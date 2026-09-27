package com.github.lisama569.apigatewayjava.api;

import com.github.lisama569.apigatewayjava.access.AccessService;
import com.github.lisama569.apigatewayjava.access.InFlightLimiter;
import com.github.lisama569.apigatewayjava.core.error.ErrorCode;
import com.github.lisama569.apigatewayjava.upstream.UpstreamClient;
import com.github.lisama569.apigatewayjava.upstream.UpstreamOutcome;
import com.github.lisama569.apigatewayjava.upstream.UpstreamRequest;
import com.github.lisama569.apigatewayjava.upstream.UpstreamResponse;
import com.github.lisama569.apigatewayjava.version.VersionedResource;
import com.github.lisama569.apigatewayjava.version.VersionRegistry;
import com.github.lisama569.apigatewayjava.version.VersionStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class GatewayService {

    private static final Logger log = LoggerFactory.getLogger(GatewayService.class);

    private final VersionRegistry versionRegistry;
    private final AccessService accessService;
    private final UpstreamClient upstreamClient;
    private final InFlightLimiter inFlightLimiter;

    @org.springframework.beans.factory.annotation.Autowired
    public GatewayService(VersionRegistry versionRegistry,
                          AccessService accessService,
                          UpstreamClient upstreamClient,
                          InFlightLimiter inFlightLimiter) {
        this.versionRegistry = versionRegistry;
        this.accessService = accessService;
        this.upstreamClient = upstreamClient;
        this.inFlightLimiter = inFlightLimiter;
    }

    /** 兼容构造器：不限制在途并发（等价于非常大的上限）。 */
    public GatewayService(VersionRegistry versionRegistry,
                          AccessService accessService,
                          UpstreamClient upstreamClient) {
        this(versionRegistry, accessService, upstreamClient,
                new InFlightLimiter(Integer.MAX_VALUE));
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

        // 批量调用的配额成本 = 子请求条数；单个请求成本保持为 1
        int subCount = batch ? parseSubCount(request.payload()) : 1;
        long cost = subCount;

        // 2) 在途并发上限：超过上限快速失败（不排队），与配额耗尽严格区分；
        //    名额在请求结束时（finally）立即释放，压力回落后自动恢复
        String inFlightKey = callerId + ":" + request.resource();
        if (!inFlightLimiter.tryAcquire(inFlightKey)) {
            log.info("[过载判定] caller={} resource={} 在途={}/{} -> CONCURRENCY_LIMIT_EXCEEDED",
                    callerId, request.resource(), inFlightLimiter.current(inFlightKey),
                    inFlightLimiter.maxInFlight());
            return reject(ErrorCode.CONCURRENCY_LIMIT_EXCEEDED,
                    "在途并发已达上限（" + inFlightLimiter.maxInFlight()
                            + "），请求被快速拒绝而非排队；请稍后重试。"
                            + "注意：这与配额耗尽（QUOTA_EXCEEDED）不同，压力回落后即自动恢复");
        }
        try {
            return doRoute(request, callerId, batch, versioned, cost, subCount);
        } finally {
            inFlightLimiter.release(inFlightKey);
        }
    }

    private GatewayResult doRoute(GatewayRequest request, String callerId, boolean batch,
                                  VersionedResource versioned, long cost, int subCount) {
        // 3) 接入控制：401 / 403 / 429 三类拒绝可区分；批量按子请求数一次性扣减
        AccessService.AccessResult access = accessService.check(callerId, request.resource(), cost);
        if (access.quota() != null) {
            log.info("[限流判定] caller={} resource={} granted={} code={} 本次消耗={} 已用={}/{} 剩余={} reason={}",
                    callerId, request.resource(), access.granted(), access.errorCode(),
                    access.quota().consumed(), access.quota().used(), access.quota().limit(),
                    access.quota().remaining(), access.reason());
        } else {
            log.info("[限流判定] caller={} resource={} granted={} code={} reason={}",
                    callerId, request.resource(), access.granted(), access.errorCode(), access.reason());
        }
        if (!access.granted()) {
            return reject(access.errorCode(), access.reason());
        }

        boolean idempotent = !"POST".equalsIgnoreCase(request.method())
                && !"PATCH".equalsIgnoreCase(request.method())
                && !"DELETE".equalsIgnoreCase(request.method());
        String key = request.resource() + ":" + request.version();

        // 3) 上游调用（熔断/超时/重试/部分失败均在 UpstreamClient 内归一化）
        if (batch) {
            return routeBatch(request, key, idempotent, versioned, subCount);
        }
        UpstreamRequest upstreamRequest = new UpstreamRequest(key, request.method(),
                request.resource(), request.version(), request.payload(), idempotent);
        UpstreamOutcome outcome = upstreamClient.call(upstreamRequest);
        log.info("[上游调用结果] key={} success={} kind={} attempts={} degraded={}",
                key, outcome.success(), outcome.failureKind(), outcome.attemptCount(),
                outcome.degraded());
        return toGatewayResult(outcome, versioned);
    }

    /** 将 payload 视为子请求条数（缺省 3），用于演示「部分失败」隔离。 */
    private int parseSubCount(String payload) {
        try {
            if (payload != null && !payload.isBlank()) {
                return Math.max(1, Integer.parseInt(payload.trim()));
            }
        } catch (NumberFormatException ignored) {
            // 非法输入按默认条数处理
        }
        return 3;
    }

    private GatewayResult routeBatch(GatewayRequest request, String key, boolean idempotent,
                                     VersionedResource versioned, int subCount) {
        List<UpstreamRequest> subs = new ArrayList<>();
        for (int i = 0; i < subCount; i++) {
            subs.add(new UpstreamRequest(key, request.method(), request.resource(),
                    request.version(), "\"item-" + i + "\"", idempotent));
        }
        UpstreamOutcome outcome = upstreamClient.callBatch(subs);
        log.info("[上游调用结果] 批量 key={} success={} kind={} attempts={} degraded={}",
                key, outcome.success(), outcome.failureKind(), outcome.attemptCount(),
                outcome.degraded());

        // 状态一：全部成功（200）
        if (outcome.success()) {
            return buildOk(versioned, outcome.responses(), outcome, true);
        }
        // 熔断整体降级（503，未触达上游）：逐条占位结果仍保留，可逐条归因；
        // 隔离期拒绝（UPSTREAM_UNAVAILABLE）与恢复期名额耗尽（UPSTREAM_RECOVERY_LIMITED）
        // 是两种不同的降级，错误码与日志均严格区分
        if (outcome.degraded()) {
            boolean recoveryLimited = outcome.failureKind()
                    == com.github.lisama569.apigatewayjava.upstream.UpstreamFailureKind.HALF_OPEN_PROBE_LIMIT;
            ErrorCode code = recoveryLimited
                    ? ErrorCode.UPSTREAM_RECOVERY_LIMITED : ErrorCode.UPSTREAM_UNAVAILABLE;
            List<Object> degradedItems = outcome.responses() == null
                    ? List.of() : new ArrayList<>(outcome.responses());
            log.info("[降级分类] 批量 key={} code={} 逐条结果数={}（未触达上游）",
                    key, code, degradedItems.size());
            return GatewayResult.builder()
                    .success(false)
                    .errorCode(code)
                    .httpStatus(code.httpStatus())
                    .message(recoveryLimited
                            ? "上游处于恢复探测期且探测名额已用完，整批请求被快速降级"
                              + "（未触达上游，无重复副作用）；与熔断隔离期拒绝不同，上游正在恢复，可稍后重试"
                            : "上游熔断器打开（隔离期），整批请求被快速降级（未触达上游，无重复副作用）")
                    .batchData(degradedItems)
                    .batchStatus(BatchStatus.DEGRADED)
                    .degraded(true)
                    .build();
        }
        List<Object> items = outcome.responses() == null
                ? List.of() : new ArrayList<>(outcome.responses());
        // 状态二：整批失败（502 UPSTREAM_BATCH_FAILURE）——与部分失败严格区分，应整批重试
        if (outcome.failureKind() == com.github.lisama569.apigatewayjava.upstream.UpstreamFailureKind.BATCH_FAILURE) {
            return GatewayResult.builder()
                    .success(false)
                    .errorCode(ErrorCode.UPSTREAM_BATCH_FAILURE)
                    .httpStatus(ErrorCode.UPSTREAM_BATCH_FAILURE.httpStatus())
                    .message("批量调用全部子请求失败（共 " + items.size()
                            + " 条），无成功项，建议整批重试；失败项逐条保留在 data 中")
                    .batchData(items)
                    .batchStatus(BatchStatus.ALL_FAILED)
                    .degraded(false)
                    .deprecated(versioned.status() == VersionStatus.DEPRECATED)
                    .deprecationNotice(versioned.deprecationNotice())
                    .build();
        }
        // 状态三：部分成功（207 UPSTREAM_PARTIAL_FAILURE）——只需重试失败项
        return GatewayResult.builder()
                .success(false)
                .errorCode(ErrorCode.UPSTREAM_PARTIAL_FAILURE)
                .httpStatus(ErrorCode.UPSTREAM_PARTIAL_FAILURE.httpStatus())
                .message("部分子请求失败，已对失败项做隔离，成功项结果保留在 data 中，仅需重试失败项")
                .batchData(items)
                .batchStatus(BatchStatus.PARTIAL_FAILURE)
                .degraded(false)
                .deprecated(versioned.status() == VersionStatus.DEPRECATED)
                .deprecationNotice(versioned.deprecationNotice())
                .build();
    }

    private GatewayResult toGatewayResult(UpstreamOutcome outcome, VersionedResource versioned) {
        if (outcome.success()) {
            return buildOk(versioned, List.of(outcome.response()), outcome, false);
        }
        if (outcome.degraded()) {
            // 两种快速降级严格区分：隔离期拒绝 vs 恢复期探测名额耗尽，
            // 调用方可据此决定继续退避还是准备重试；两者均未触达上游
            boolean recoveryLimited = outcome.failureKind()
                    == com.github.lisama569.apigatewayjava.upstream.UpstreamFailureKind.HALF_OPEN_PROBE_LIMIT;
            ErrorCode code = recoveryLimited
                    ? ErrorCode.UPSTREAM_RECOVERY_LIMITED : ErrorCode.UPSTREAM_UNAVAILABLE;
            log.info("[降级分类] code={}（未触达上游，无重复副作用）", code);
            return GatewayResult.builder()
                    .success(false)
                    .errorCode(code)
                    .httpStatus(code.httpStatus())
                    .message(recoveryLimited
                            ? "上游处于恢复探测期且探测名额已用完，请求被快速降级"
                              + "（未触达上游，无重复副作用）；上游正在恢复，可稍后重试"
                            : "上游熔断器打开（隔离期），请求被快速降级（未触达上游，无重复副作用）")
                    .degraded(true)
                    .build();
        }
        ErrorCode code = switch (outcome.failureKind()) {
            case TIMEOUT -> ErrorCode.UPSTREAM_TIMEOUT;
            case PARTIAL_FAILURE -> ErrorCode.UPSTREAM_PARTIAL_FAILURE;
            case BATCH_FAILURE -> ErrorCode.UPSTREAM_BATCH_FAILURE;
            case ERROR_5XX -> ErrorCode.UPSTREAM_ERROR;
            // 降级类 kind 已在上方 degraded 分支处理，此处仅为穷尽枚举
            case CIRCUIT_OPEN -> ErrorCode.UPSTREAM_UNAVAILABLE;
            case HALF_OPEN_PROBE_LIMIT -> ErrorCode.UPSTREAM_RECOVERY_LIMITED;
        };
        String message = switch (code) {
            case UPSTREAM_TIMEOUT -> "上游调用超时，已按重试策略处理后仍失败";
            case UPSTREAM_ERROR -> "上游返回错误（5xx），已归一化为 UPSTREAM_ERROR";
            default -> "部分上游请求失败";
        };
        return reject(code, message);
    }

    private GatewayResult buildOk(VersionedResource versioned, List<?> data,
                                  UpstreamOutcome outcome, boolean batch) {
        boolean deprecated = versioned.status() == VersionStatus.DEPRECATED;
        List<Object> items = new ArrayList<>(data);
        return GatewayResult.builder()
                .success(true)
                .httpStatus(200)
                .data(batch ? null : (items.isEmpty() ? null : items.get(0)))
                .batchData(batch ? items : null)
                .batchStatus(batch ? BatchStatus.ALL_SUCCEEDED : null)
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
