package com.github.lisama569.apigatewayjava.api;

/**
 * 批量调用中单条子请求的逐条视图：无论成功或失败都保留，且失败项可归因。
 *
 * @param index     子请求序号（从 0 开始）
 * @param success   该子请求是否成功
 * @param status    归一化后的 HTTP 风格状态码（成功 200；失败为对应错误码状态）
 * @param errorCode 失败时的错误分类（成功为 null）
 * @param degraded  是否因熔断/恢复期限制而未触达上游（无重复副作用）
 * @param data      成功时的业务数据（失败时为占位错误说明）
 */
public record BatchItemView(int index, boolean success, int status, String errorCode,
                            boolean degraded, Object data) {
}
