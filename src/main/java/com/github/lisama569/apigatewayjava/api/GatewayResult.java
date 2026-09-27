package com.github.lisama569.apigatewayjava.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.github.lisama569.apigatewayjava.core.error.ErrorCode;

import java.util.List;

/**
 * 网关统一出参：成功/失败共用同一信封。
 * success=false 时携带可区分的 errorCode 与对应 HTTP 状态；
 * 成功但版本废弃时 deprecated=true 并给出可识别提示；
 * degraded=true 表示响应来自熔断/降级路径。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GatewayResult {

    private boolean success;
    private ErrorCode errorCode;
    private int httpStatus;
    private String message;
    private Object data;
    private boolean deprecated;
    private String deprecationNotice;
    private boolean degraded;
    /**
     * 降级原因（机器可读），仅 degraded=true 时存在：
     * {@code CIRCUIT_OPEN}（熔断 OPEN 挡回，应继续退避）或
     * {@code RECOVERY_PROBE_CAPACITY_FULL}（恢复期探测名额用尽，可尽快重试）。
     */
    private String degradeReason;
    private List<Object> batchData;

    public static GatewayResultBuilder builder() {
        return new GatewayResultBuilder();
    }

    public boolean isSuccess() { return success; }
    public ErrorCode getErrorCode() { return errorCode; }
    public int getHttpStatus() { return httpStatus; }
    public String getMessage() { return message; }
    public Object getData() { return data; }
    public boolean isDeprecated() { return deprecated; }
    public String getDeprecationNotice() { return deprecationNotice; }
    public boolean isDegraded() { return degraded; }
    public String getDegradeReason() { return degradeReason; }
    public List<Object> getBatchData() { return batchData; }

    public static final class GatewayResultBuilder {
        private final GatewayResult r = new GatewayResult();

        public GatewayResultBuilder success(boolean success) { r.success = success; return this; }
        public GatewayResultBuilder errorCode(ErrorCode errorCode) { r.errorCode = errorCode; return this; }
        public GatewayResultBuilder httpStatus(int httpStatus) { r.httpStatus = httpStatus; return this; }
        public GatewayResultBuilder message(String message) { r.message = message; return this; }
        public GatewayResultBuilder data(Object data) { r.data = data; return this; }
        public GatewayResultBuilder deprecated(boolean deprecated) { r.deprecated = deprecated; return this; }
        public GatewayResultBuilder deprecationNotice(String notice) { r.deprecationNotice = notice; return this; }
        public GatewayResultBuilder degraded(boolean degraded) { r.degraded = degraded; return this; }
        public GatewayResultBuilder degradeReason(String degradeReason) { r.degradeReason = degradeReason; return this; }
        public GatewayResultBuilder batchData(List<Object> batchData) { r.batchData = batchData; return this; }

        public GatewayResult build() { return r; }
    }
}
