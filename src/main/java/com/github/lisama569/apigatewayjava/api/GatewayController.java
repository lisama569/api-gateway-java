package com.github.lisama569.apigatewayjava.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 版本化统一入口：/api/gateway/{resource}/{version}
 * 所有版本、所有资源共用同一路由，版本差异由注册表与上游决定。
 */
@RestController
@RequestMapping("/api/gateway")
public class GatewayController {

    private final GatewayService gatewayService;

    public GatewayController(GatewayService gatewayService) {
        this.gatewayService = gatewayService;
    }

    @RequestMapping(value = "/{resource}/{version}", method = {
            RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT,
            RequestMethod.DELETE, RequestMethod.PATCH})
    public ResponseEntity<GatewayResult> route(
            @PathVariable String resource,
            @PathVariable String version,
            @RequestHeader(value = "X-Caller-Id", required = false) String callerId,
            @RequestHeader(value = "X-Batch", required = false, defaultValue = "false") boolean batch,
            @RequestHeader(value = "X-Demo-Fault", required = false) String demoFault,
            @RequestHeader(value = "X-Demo-Latency-Millis", required = false,
                    defaultValue = "0") long demoLatencyMillis,
            @RequestBody(required = false) String payload,
            HttpServletRequest httpRequest) {

        GatewayRequest request = new GatewayRequest(resource, version,
                httpRequest.getMethod(), payload, demoFault, demoLatencyMillis);
        GatewayResult result = gatewayService.route(request, callerId, batch);
        return ResponseEntity.status(result.getHttpStatus()).body(result);
    }

    /** 便捷批量入口。 */
    @PostMapping("/{resource}/{version}/batch")
    public ResponseEntity<GatewayResult> batch(
            @PathVariable String resource,
            @PathVariable String version,
            @RequestHeader(value = "X-Caller-Id", required = false) String callerId,
            @RequestHeader(value = "X-Demo-Fault", required = false) String demoFault,
            @RequestHeader(value = "X-Demo-Latency-Millis", required = false,
                    defaultValue = "0") long demoLatencyMillis,
            @RequestBody(required = false) String payload) {
        GatewayRequest request = new GatewayRequest(resource, version, "GET", payload,
                demoFault, demoLatencyMillis);
        GatewayResult result = gatewayService.route(request, callerId, true);
        return ResponseEntity.status(result.getHttpStatus()).body(result);
    }
}
