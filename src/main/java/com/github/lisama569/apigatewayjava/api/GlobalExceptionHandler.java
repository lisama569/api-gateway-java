package com.github.lisama569.apigatewayjava.api;

import com.github.lisama569.apigatewayjava.core.error.ErrorCode;
import com.github.lisama569.apigatewayjava.core.error.GatewayException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 兜底异常归一化：未被业务链路显式转换的异常统一映射为 UPSTREAM_ERROR(502)，
 * 避免向调用方泄漏堆栈，并保持响应信封一致。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(GatewayException.class)
    public ResponseEntity<GatewayResult> handleGateway(GatewayException e) {
        GatewayResult result = GatewayResult.builder()
                .success(false)
                .errorCode(e.getErrorCode())
                .httpStatus(e.getErrorCode().httpStatus())
                .message(e.getMessage())
                .build();
        return ResponseEntity.status(result.getHttpStatus()).body(result);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<GatewayResult> handleAny(Exception e) {
        log.error("[网关异常] 未预期异常，归一化为 UPSTREAM_ERROR", e);
        GatewayResult result = GatewayResult.builder()
                .success(false)
                .errorCode(ErrorCode.UPSTREAM_ERROR)
                .httpStatus(ErrorCode.UPSTREAM_ERROR.httpStatus())
                .message("网关内部错误：" + e.getClass().getSimpleName())
                .build();
        return ResponseEntity.status(result.getHttpStatus()).body(result);
    }
}
