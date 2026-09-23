package com.github.lisama569.apigatewayjava.upstream;

public class UpstreamException extends RuntimeException {
    private final UpstreamFailureKind kind;

    public UpstreamException(UpstreamFailureKind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public UpstreamFailureKind getKind() {
        return kind;
    }
}
