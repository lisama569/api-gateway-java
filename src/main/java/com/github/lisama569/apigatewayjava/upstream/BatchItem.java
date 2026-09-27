package com.github.lisama569.apigatewayjava.upstream;

import java.util.ArrayList;
import java.util.List;

/**
 * 批量调用中单个子请求的归一化结果，与请求中的子请求按位置逐条对齐。
 *
 * <ul>
 *   <li>{@code success=true}：{@code response} 为上游响应；</li>
 *   <li>{@code success=false}：{@code errorCode} 为该条的独立错误分类
 *       （UPSTREAM_ERROR / UPSTREAM_TIMEOUT / UPSTREAM_UNAVAILABLE / RECOVERY_LIMITED），
 *       {@code message} 为可归因原因，{@code response} 为同构占位。</li>
 * </ul>
 *
 * 即使整批被快速降级（未触达上游），每条子请求也保留各自的降级原因，
 * 绝不因整批降级而丢失逐条明细。
 */
public record BatchItem(boolean success, UpstreamResponse response,
                        String errorCode, String message, int attempts, boolean degraded) {

    public static BatchItem ok(UpstreamResponse response, int attempts) {
        return new BatchItem(true, response, null, null, attempts, false);
    }

    public static BatchItem failed(UpstreamFailureKind kind, String message,
                                   int attempts, boolean degraded) {
        return new BatchItem(false,
                new UpstreamResponse(500, "{\"error\":\"" + kind.name() + "\"}"),
                kind.name(), message, attempts, degraded);
    }

    /** 兼容旧批量结果：从条目列表提取成功/占位响应。 */
    static List<UpstreamResponse> toResponses(List<BatchItem> items) {
        List<UpstreamResponse> responses = new ArrayList<>(items.size());
        for (BatchItem item : items) {
            responses.add(item.response());
        }
        return responses;
    }
}
