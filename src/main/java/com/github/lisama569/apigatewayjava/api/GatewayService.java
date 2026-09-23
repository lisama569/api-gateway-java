package com.github.lisama569.apigatewayjava.api;

import com.github.lisama569.apigatewayjava.access.AccessService;
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

    public GatewayService(VersionRegistry versionRegistry,
                          AccessService accessService,
                          UpstreamClient upstreamClient) {
        this.versionRegistry = versionRegistry;
        this.accessService = accessService;
        this.upstreamClient = upstreamClient;
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

        // 2) 接入控制：401 / 403 / 429 三类拒绝可区分
        AccessService.AccessResult access = accessService.check(callerId, request.resource());
        log.info("[限流判定] caller={} resource={} granted={} code={} reason={}",
                callerId, request.resource(), access.granted(), access.errorCode(), access.reason());
        if (!access.granted()) {
            return reject(access.errorCode(), access.reason());
        }

        boolean idempotent = !"POST".equalsIgnoreCase(request.method())
                && !"PATCH".equalsIgnoreCase(request.method())
                && !"DELETE".equalsIgnoreCase(request.method());
        String key = request.resource() + ":" + request.version();

        // 3) 上游调用（熔断/超时/重试/部分失败均在 UpstreamClient 内归一化）
        if (batch) {
            return routeBatch(request, key, idempotent, versioned);
        }
        UpstreamRequest upstreamRequest = new UpstreamRequest(key, request.method(),
                request.resource(), request.version(), request.payload(), idempotent);
        UpstreamOutcome outcome = upstreamClient.call(upstreamRequest);
        log.info("[上游调用结果] key={} success={} kind={} attempts={} degraded={}",
                key, outcome.success(), outcome.failureKind(), outcome.attemptCount(),
                outcome.degraded());
        return toGatewayResult(outcome, versioned);
    }

    private GatewayResult routeBatch(GatewayRequest request, String key, boolean idempotent,
                                     VersionedResource versioned) {
        // 将 payload 视为子请求条数（缺省 3），用于演示「部分失败」隔离
        int subCount = 3;
        try {
            if (request.payload() != null && !request.payload().isBlank()) {
                subCount = Integer.parseInt(request.payload().trim());
            }
        } catch (NumberFormatException ignored) {
            subCount = 3;
        }
        List<UpstreamRequest> subs = new ArrayList<>();
        for (int i = 0; i < subCount; i++) {
            subs.add(new UpstreamRequest(key, request.method(), request.resource(),
                    request.version(), "\"item-" + i + "\"", idempotent));
        }
        UpstreamOutcome outcome = upstreamClient.callBatch(subs);
        log.info("[上游调用结果] 批量 key={} success={} kind={} attempts={}",
                key, outcome.success(), outcome.failureKind(), outcome.attemptCount());

        if (outcome.success()) {
            return buildOk(versioned, outcome.responses(), outcome, true);
        }
        List<Object> items = new ArrayList<>(outcome.responses());
        GatewayResult.GatewayResultBuilder b = GatewayResult.builder()
                .success(false)
                .errorCode(ErrorCode.UPSTREAM_PARTIAL_FAILURE)
                .httpStatus(ErrorCode.UPSTREAM_PARTIAL_FAILURE.httpStatus())
                .message("部分子请求失败，已对失败项做隔离，成功项结果保留在 data 中")
                .batchData(items)
                .degraded(false)
                .deprecated(versioned.status() == VersionStatus.DEPRECATED)
                .deprecationNotice(versioned.deprecationNotice());
        return b.build();
    }

    private GatewayResult toGatewayResult(UpstreamOutcome outcome, VersionedResource versioned) {
        if (outcome.success()) {
            return buildOk(versioned, List.of(outcome.response()), outcome, false);
        }
        if (outcome.degraded()) {
            return GatewayResult.builder()
                    .success(false)
                    .errorCode(ErrorCode.UPSTREAM_UNAVAILABLE)
                    .httpStatus(ErrorCode.UPSTREAM_UNAVAILABLE.httpStatus())
                    .message("上游熔断器打开，请求被快速降级（未触达上游，无重复副作用）")
                    .degraded(true)
                    .build();
        }
        ErrorCode code = switch (outcome.failureKind()) {
            case TIMEOUT -> ErrorCode.UPSTREAM_TIMEOUT;
            case PARTIAL_FAILURE -> ErrorCode.UPSTREAM_PARTIAL_FAILURE;
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
