package com.github.lisama569.apigatewayjava.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.github.lisama569.apigatewayjava.core.error.ErrorCode;

import java.util.List;

/**
 * 网关统一出参：成功/失败共用同一信封。
 * success=false 时携带可区分的 errorCode 与对应 HTTP 状态；
 * 成功但版本废弃时 deprecated=true 并给出可识别提示；
 * degraded=true 表示响应来自熔断/恢复期降级路径。
 *
 * 批量调用额外携带 {@code batchStatus}（ALL_SUCCESS/PARTIAL_SUCCESS/ALL_FAILED）、
 * 逐条 {@code batchItems}（成功/失败逐条可归因）以及 total/succeeded/failed 计数。
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
    private List<Object> batchData;
    private BatchStatus batchStatus;
    private List<BatchItemView> batchItems;
    private Integer batchTotal;
    private Integer batchSucceeded;
    private Integer batchFailed;

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
    public List<Object> getBatchData() { return batchData; }
    public BatchStatus getBatchStatus() { return batchStatus; }
    public List<BatchItemView> getBatchItems() { return batchItems; }
    public Integer getBatchTotal() { return batchTotal; }
    public Integer getBatchSucceeded() { return batchSucceeded; }
    public Integer getBatchFailed() { return batchFailed; }

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
        public GatewayResultBuilder batchData(List<Object> batchData) { r.batchData = batchData; return this; }
        public GatewayResultBuilder batchStatus(BatchStatus batchStatus) { r.batchStatus = batchStatus; return this; }
        public GatewayResultBuilder batchItems(List<BatchItemView> batchItems) { r.batchItems = batchItems; return this; }
        public GatewayResultBuilder batchCounts(int total, int succeeded, int failed) {
            r.batchTotal = total;
            r.batchSucceeded = succeeded;
            r.batchFailed = failed;
            return this;
        }

        public GatewayResult build() { return r; }
    }
}
