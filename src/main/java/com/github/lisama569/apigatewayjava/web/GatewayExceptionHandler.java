package com.github.lisama569.apigatewayjava.web;

import com.github.lisama569.apigatewayjava.model.GatewayResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;
import java.util.Map;

/**
 * Maps unexpected exceptions to the unified envelope so failures stay explainable.
 */
@RestControllerAdvice
public class GatewayExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger("GATEWAY");

    @ExceptionHandler(Exception.class)
    public ResponseEntity<GatewayResponse> handle(Exception e) {
        log.error("[GATEWAY] unexpected failure", e);
        GatewayResponse response = new GatewayResponse(
                false, "INTERNAL_ERROR", 500, null, null, null,
                List.of(), Map.of("reason", String.valueOf(e.getMessage())), 0, false);
        return ResponseEntity.status(500).body(response);
    }
}
