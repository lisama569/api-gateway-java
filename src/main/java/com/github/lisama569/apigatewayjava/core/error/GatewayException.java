package com.github.lisama569.apigatewayjava.core.error;

public class GatewayException extends RuntimeException {
    private final ErrorCode errorCode;

    public GatewayException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
