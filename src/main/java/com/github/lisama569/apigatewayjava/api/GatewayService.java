package com.github.lisama569.apigatewayjava.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.lisama569.apigatewayjava.access.AccessService;
import com.github.lisama569.apigatewayjava.access.InFlightLimiter;
import com.github.lisama569.apigatewayjava.core.error.ErrorCode;
import com.github.lisama569.apigatewayjava.upstream.BatchItem;
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
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class GatewayService {

    private static final Logger log = LoggerFactory.getLogger(GatewayService.class);

    private static final ObjectMapper JSON = new ObjectMapper();

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

        // 本地复现用的请求级故障注入：解析 X-Demo-Fault（支持 类型[:失败条数]）。
        // 批量时 faultCount 表示前多少条子请求注入故障（缺省=全部）；单请求时取 1。
        UpstreamFailureKind demoKind = parseDemoFaultKind(request.demoFaultHeader());
        int faultCount = parseDemoFaultCount(request.demoFaultHeader(), subCount, batch);

        // 3) 上游调用（熔断/超时/重试/部分失败均在 UpstreamClient 内归一化）
        if (batch) {
            return routeBatch(request, key, idempotent, versioned, subCount,
                    demoKind, faultCount);
        }
        UpstreamRequest upstreamRequest = new UpstreamRequest(key, request.method(),
                request.resource(), request.version(), request.payload(), idempotent)
                .withDemoFault(demoKind, request.demoLatencyMillis());
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

    /**
     * 解析本地复现头 X-Demo-Fault 的故障类型部分（冒号前）。
     * 头不存在/无法识别时返回 null，正常请求路径完全不受影响。
     */
    private UpstreamFailureKind parseDemoFaultKind(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        String typePart = header.trim().split(":", 2)[0].trim().toUpperCase();
        try {
            return UpstreamFailureKind.valueOf(typePart);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 解析 X-Demo-Fault 的「失败条数」部分（冒号后，仅批量有意义）：
     * 缺省/非法时批量=全部子请求、单请求=1；返回值裁剪到 [0, subCount]。
     */
    private int parseDemoFaultCount(String header, int subCount, boolean batch) {
        if (header == null || !header.contains(":")) {
            return batch ? subCount : 1;
        }
        try {
            int n = Integer.parseInt(header.substring(header.indexOf(':') + 1).trim());
            return Math.max(0, Math.min(subCount, n));
        } catch (NumberFormatException e) {
            return batch ? subCount : 1;
        }
    }

    private GatewayResult routeBatch(GatewayRequest request, String key, boolean idempotent,
                                     VersionedResource versioned, int subCount,
                                     UpstreamFailureKind demoKind, int faultCount) {
        List<UpstreamRequest> subs = new ArrayList<>();
        for (int i = 0; i < subCount; i++) {
            // 前 faultCount 条子请求注入演示故障（含其每次重试），其余正常，
            // 从而无需改配置即可本地复现「部分成功 / 整批失败」
            UpstreamFailureKind itemFault = i < faultCount ? demoKind : null;
            subs.add(new UpstreamRequest(key, request.method(), request.resource(),
                    request.version(), "\"item-" + i + "\"", idempotent)
                    .withDemoFault(itemFault, request.demoLatencyMillis()));
        }
        UpstreamOutcome outcome = upstreamClient.callBatch(subs);
        log.info("[上游调用结果] 批量 key={} success={} kind={} attempts={} degraded={}",
                key, outcome.success(), outcome.failureKind(), outcome.attemptCount(),
                outcome.degraded());

        // 无论全部成功/部分失败/整批失败/整批降级，batchData 都与子请求逐条对齐，
        // 每条都带独立归因（成功项为数据，失败项为该条 errorCode/message/attempts/degraded）
        List<Object> items = toItemViews(outcome);
        boolean deprecated = versioned.status() == VersionStatus.DEPRECATED;
        String deprecationNotice = deprecated ? versioned.deprecationNotice() : null;

        // 状态一：全部成功（200）
        if (outcome.success()) {
            return GatewayResult.builder()
                    .success(true)
                    .httpStatus(200)
                    .message(deprecated
                            ? "调用成功，但该版本已废弃：" + deprecationNotice
                            : "全部 " + items.size() + " 条子请求成功")
                    .batchData(items)
                    .deprecated(deprecated)
                    .deprecationNotice(deprecationNotice)
                    .degraded(false)
                    .build();
        }
        // 整体快速降级（503，未触达上游）：明细仍逐条保留、逐条归因
        if (outcome.degraded()) {
            boolean recoveryLimited =
                    outcome.failureKind() == UpstreamFailureKind.RECOVERY_LIMITED;
            ErrorCode code = recoveryLimited
                    ? ErrorCode.UPSTREAM_RECOVERY_LIMITED
                    : ErrorCode.UPSTREAM_UNAVAILABLE;
            String reason = recoveryLimited
                    ? BatchItemView.REASON_RECOVERY_PROBE_CAPACITY_FULL
                    : BatchItemView.REASON_CIRCUIT_OPEN;
            log.info("[降级判定] 批量 key={} -> {} reason={}（整批未触达上游，逐条结果已保留）",
                    key, code, reason);
            return GatewayResult.builder()
                    .success(false)
                    .errorCode(code)
                    .httpStatus(code.httpStatus())
                    .message(recoveryLimited
                            ? "恢复期(HALF_OPEN)探测名额已用尽，整批 " + items.size()
                                    + " 条子请求均被快速降级（未触达上游、无副作用）；"
                                    + "可在极短退避后尽快重试，逐条归因见 data"
                            : "上游熔断器打开(OPEN)，整批 " + items.size()
                                    + " 条子请求被快速降级（未触达上游、无副作用）；"
                                    + "建议继续退避，逐条归因见 data")
                    .degraded(true)
                    .degradeReason(reason)
                    .batchData(items)
                    .deprecated(deprecated)
                    .deprecationNotice(deprecationNotice)
                    .build();
        }
        // 状态二：整批失败（502 UPSTREAM_BATCH_FAILURE）——与部分失败严格区分，应整批重试
        if (outcome.failureKind() == UpstreamFailureKind.BATCH_FAILURE) {
            return GatewayResult.builder()
                    .success(false)
                    .errorCode(ErrorCode.UPSTREAM_BATCH_FAILURE)
                    .httpStatus(ErrorCode.UPSTREAM_BATCH_FAILURE.httpStatus())
                    .message("批量调用全部 " + items.size()
                            + " 条子请求失败，无成功项，建议整批重试（不得伪装成部分成功）；"
                            + "失败项逐条保留在 data 中可按位置归因")
                    .batchData(items)
                    .degraded(false)
                    .deprecated(deprecated)
                    .deprecationNotice(deprecationNotice)
                    .build();
        }
        // 状态三：部分成功（207 UPSTREAM_PARTIAL_FAILURE）——只需重试失败项
        return GatewayResult.builder()
                .success(false)
                .errorCode(ErrorCode.UPSTREAM_PARTIAL_FAILURE)
                .httpStatus(ErrorCode.UPSTREAM_PARTIAL_FAILURE.httpStatus())
                .message("部分子请求失败，已对失败项做隔离，成功项结果保留在 data 中，"
                        + "仅需按位置重试失败项；每条结果均带独立归因")
                .batchData(items)
                .degraded(false)
                .deprecated(deprecated)
                .deprecationNotice(deprecationNotice)
                .build();
    }

    /** 将上游逐条结果转换为对外视图，失败占位体尽量解析为 JSON 结构，解析不了则原样保留。 */
    private List<Object> toItemViews(UpstreamOutcome outcome) {
        List<Object> views = new ArrayList<>();
        if (outcome.items() == null) {
            return views;
        }
        for (int i = 0; i < outcome.items().size(); i++) {
            BatchItem item = outcome.items().get(i);
            Object parsed = null;
            if (item.success() && item.response() != null) {
                parsed = parseJson(item.response().body());
            }
            views.add(BatchItemView.of(i, item, parsed));
        }
        return views;
    }

    private Object parseJson(String body) {
        if (body == null) {
            return null;
        }
        try {
            return JSON.readValue(body, Object.class);
        } catch (Exception e) {
            return body;
        }
    }

    private GatewayResult toGatewayResult(UpstreamOutcome outcome, VersionedResource versioned) {
        if (outcome.success()) {
            return buildOk(versioned, List.of(outcome.response()), outcome, false);
        }
        if (outcome.degraded()) {
            // 两类 503 快速降级严格区分：错误码、degradeReason、消息与日志一一对应，
            // 调用方据此判断「继续退避」还是「尽快重试」；两者均未触达上游。
            if (outcome.failureKind() == UpstreamFailureKind.RECOVERY_LIMITED) {
                log.info("[降级判定] key={} -> UPSTREAM_RECOVERY_LIMITED"
                        + " reason=RECOVERY_PROBE_CAPACITY_FULL（未触达上游，可尽快重试）",
                        versioned.resource() + ":" + versioned.version());
                return GatewayResult.builder()
                        .success(false)
                        .errorCode(ErrorCode.UPSTREAM_RECOVERY_LIMITED)
                        .httpStatus(ErrorCode.UPSTREAM_RECOVERY_LIMITED.httpStatus())
                        .message("上游处于恢复期(HALF_OPEN)，本轮有限探测名额已用尽，"
                                + "请求被快速降级且未触达上游（无重复副作用）；"
                                + "可在极短退避后尽快重试，无需等待下一个冷却周期")
                        .degraded(true)
                        .degradeReason(BatchItemView.REASON_RECOVERY_PROBE_CAPACITY_FULL)
                        .build();
            }
            log.info("[降级判定] key={} -> UPSTREAM_UNAVAILABLE reason=CIRCUIT_OPEN"
                    + "（未触达上游，应继续退避）",
                    versioned.resource() + ":" + versioned.version());
            return GatewayResult.builder()
                    .success(false)
                    .errorCode(ErrorCode.UPSTREAM_UNAVAILABLE)
                    .httpStatus(ErrorCode.UPSTREAM_UNAVAILABLE.httpStatus())
                    .message("上游熔断器打开(OPEN)，请求被快速降级且未触达上游"
                            + "（无重复副作用）；上游仍在隔离期，建议继续退避等待恢复")
                    .degraded(true)
                    .degradeReason(BatchItemView.REASON_CIRCUIT_OPEN)
                    .build();
        }
        ErrorCode code = switch (outcome.failureKind()) {
            case TIMEOUT -> ErrorCode.UPSTREAM_TIMEOUT;
            case PARTIAL_FAILURE -> ErrorCode.UPSTREAM_PARTIAL_FAILURE;
            case BATCH_FAILURE -> ErrorCode.UPSTREAM_BATCH_FAILURE;
            case UPSTREAM_UNAVAILABLE, RECOVERY_LIMITED -> ErrorCode.UPSTREAM_UNAVAILABLE;
            case ERROR_5XX -> ErrorCode.UPSTREAM_ERROR;
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
