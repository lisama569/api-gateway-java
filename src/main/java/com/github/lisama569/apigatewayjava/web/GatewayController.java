package com.github.lisama569.apigatewayjava.web;

import com.github.lisama569.apigatewayjava.core.GatewayService;
import com.github.lisama569.apigatewayjava.model.GatewayResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * Versioned unified entry point: POST /gateway/{resource}/{version}
 *
 * <p>Headers:
 * <ul>
 *   <li>{@code X-Api-Key} - caller identity</li>
 *   <li>{@code X-Test-Scenario} - local-only scripted-upstream scenario selector
 *       (ok/timeout/error/partial/fail:N)</li>
 * </ul>
 */
@RestController
@RequestMapping("/gateway")
public class GatewayController {

    private final GatewayService gatewayService;

    public GatewayController(GatewayService gatewayService) {
        this.gatewayService = gatewayService;
    }

    @PostMapping("/{resource}/{version}")
    public ResponseEntity<GatewayResponse> invoke(
            @PathVariable String resource,
            @PathVariable String version,
            @RequestHeader(value = "X-Api-Key", required = false) String apiKey,
            @RequestHeader(value = "X-Test-Scenario", required = false) String scenario,
            String body) {
        GatewayResponse response = gatewayService.invoke(resource, version, apiKey, scenario, body);
        return ResponseEntity.status(response.httpStatus()).body(response);
    }
}
