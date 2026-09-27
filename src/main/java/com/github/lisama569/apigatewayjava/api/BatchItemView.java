package com.github.lisama569.apigatewayjava.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.github.lisama569.apigatewayjava.upstream.BatchItem;
import com.github.lisama569.apigatewayjava.upstream.UpstreamFailureKind;

/**
 * 批量响应中单条子请求的对外视图，按 {@code index} 与请求中的子请求逐条对齐。
 *
 * 成功项：{@code success=true, httpStatus=200, data=上游返回}；
 * 失败项：{@code success=false}，携带该条独立的 {@code errorCode}/{@code message}，
 * 以及真实尝试次数 {@code attempts} 与是否未触达上游的 {@code degraded}。
 * 整批快速降级时每条仍是一个完整的失败条目（503 归因），明细不会被丢弃。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BatchItemView(int index, boolean success, int httpStatus, Object data,
                            String errorCode, String message, int attempts, boolean degraded) {

    /** 降级原因常量，与 GatewayResult.degradeReason 对齐。 */
    public static final String REASON_CIRCUIT_OPEN = "CIRCUIT_OPEN";
    public static final String REASON_RECOVERY_PROBE_CAPACITY_FULL = "RECOVERY_PROBE_CAPACITY_FULL";

    public static BatchItemView of(int index, BatchItem item, Object parsedData) {
        if (item.success()) {
            return new BatchItemView(index, true, 200, parsedData, null, null,
                    item.attempts(), false);
        }
        UpstreamFailureKind kind = parseKind(item.errorCode());
        return new BatchItemView(index, false, httpStatusOf(kind), null,
                item.errorCode(), item.message(), item.attempts(), item.degraded());
    }

    private static UpstreamFailureKind parseKind(String name) {
        try {
            return UpstreamFailureKind.valueOf(name);
        } catch (RuntimeException e) {
            return UpstreamFailureKind.ERROR_5XX;
        }
    }

    private static int httpStatusOf(UpstreamFailureKind kind) {
        return switch (kind) {
            case TIMEOUT -> 504;
            case UPSTREAM_UNAVAILABLE, RECOVERY_LIMITED -> 503;
            default -> 502;
        };
    }
}
