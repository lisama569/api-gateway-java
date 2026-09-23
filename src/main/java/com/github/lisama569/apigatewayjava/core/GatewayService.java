package com.github.lisama569.apigatewayjava.core;

import com.github.lisama569.apigatewayjava.auth.Authenticator;
import com.github.lisama569.apigatewayjava.model.AuthResult;
import com.github.lisama569.apigatewayjava.model.GatewayErrorCode;
import com.github.lisama569.apigatewayjava.model.GatewayResponse;
import com.github.lisama569.apigatewayjava.model.QuotaDecision;
import com.github.lisama569.apigatewayjava.model.UpstreamRequest;
import com.github.lisama569.apigatewayjava.model.UpstreamResult;
import com.github.lisama569.apigatewayjava.model.UpstreamResultType;
import com.github.lisama569.apigatewayjava.model.VersionDescriptor;
import com.github.lisama569.apigatewayjava.model.VersionOutcome;
import com.github.lisama569.apigatewayjava.model.VersionResolution;
import com.github.lisama569.apigatewayjava.ratelimit.QuotaService;
import com.github.lisama569.apigatewayjava.resilience.ResilientUpstreamInvoker;
import com.github.lisama569.apigatewayjava.version.VersionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gateway orchestration in a fixed, explainable order:
 * version resolution -> authentication/authorization -> quota -> resilient upstream call.
 *
 * <p>Each rejected stage returns a stable, distinguishable {@link GatewayErrorCode},
 * and every decision is logged: request input, rate-limit decision, upstream result.
 */
@Service
public class GatewayService {

    private static final Logger log = LoggerFactory.getLogger("GATEWAY");

    private final VersionRegistry versions;
    private final Authenticator authenticator;
    private final QuotaService quotaService;
    private final ResilientUpstreamInvoker invoker;

    public GatewayService(VersionRegistry versions,
                          Authenticator authenticator,
                          QuotaService quotaService,
                          ResilientUpstreamInvoker invoker) {
        this.versions = versions;
        this.authenticator = authenticator;
        this.quotaService = quotaService;
        this.invoker = invoker;
    }

    public GatewayResponse invoke(String resource, String version, String apiKey,
                                  String scenario, String payload) {
        log.info("[GATEWAY] request input: resource={} version={} apiKey={} scenario={} payload={}",
                resource, version, mask(apiKey), scenario, payload);

        // 1. version resolution
        VersionResolution resolution = versions.resolve(resource, version);
        if (resolution.outcome() == VersionOutcome.UNKNOWN_VERSION) {
            return reject(resource, version, GatewayErrorCode.VERSION_UNKNOWN, 404, resolution.message());
        }
        if (resolution.outcome() == VersionOutcome.VERSION_REMOVED) {
            return reject(resource, version, GatewayErrorCode.VERSION_REMOVED, 410, resolution.message());
        }

        VersionDescriptor descriptor = resolution.descriptor();
        List<String> notices = new ArrayList<>();
        if (descriptor.status() == com.github.lisama569.apigatewayjava.model.VersionStatus.DEPRECATED) {
            notices.add(resolution.message());
        }

        // 2. authentication / authorization
        AuthResult auth = authenticator.authenticate(apiKey, resource);
        if (auth.outcome() == com.github.lisama569.apigatewayjava.model.AuthOutcome.INVALID_IDENTITY) {
            return reject(resource, version, GatewayErrorCode.AUTH_INVALID_IDENTITY, 401, auth.reason());
        }
        if (auth.outcome() == com.github.lisama569.apigatewayjava.model.AuthOutcome.FORBIDDEN) {
            return reject(resource, version, GatewayErrorCode.AUTH_FORBIDDEN, 403, auth.reason());
        }

        // 3. quota (atomic under concurrency)
        QuotaDecision decision = quotaService.tryAcquire(auth.caller().apiKey(), auth.caller().quota());
        log.info("[GATEWAY] rate-limit decision: caller={} allowed={} used={}/{} window={} reason={}",
                auth.caller().name(), decision.allowed(), decision.used(), decision.limit(),
                decision.windowId(), decision.reason());
        if (!decision.allowed()) {
            Map<String, Object> explain = new LinkedHashMap<>();
            explain.put("auth", auth.reason());
            explain.put("quota", decision);
            return reject(resource, version, GatewayErrorCode.QUOTA_EXCEEDED, 429,
                    decision.reason(), explain, notices);
        }

        // 4. resilient upstream call
        String upstreamPayload = scenario != null && !scenario.isBlank()
                ? "scenario:" + scenario
                : payload;
        UpstreamRequest request = new UpstreamRequest(descriptor.upstream(), resource, version,
                descriptor.idempotent(), 0, upstreamPayload);
        UpstreamResult result = invoker.invoke(request);

        return toResponse(descriptor, decision, auth, notices, result);
    }

    private GatewayResponse toResponse(VersionDescriptor descriptor,
                                       QuotaDecision decision,
                                       AuthResult auth,
                                       List<String> notices,
                                       UpstreamResult result) {
        Map<String, Object> explain = new LinkedHashMap<>();
        explain.put("auth", auth.reason());
        explain.put("quota", decision);
        explain.put("attempts", result.attempts());
        explain.put("circuitNote", result.message());

        return switch (result.type()) {
            case SUCCESS -> new GatewayResponse(true, "OK", 200,
                    descriptor.resource(), descriptor.version(), result.body(),
                    notices, explain, result.attempts(), false);
            case PARTIAL_FAILURE -> new GatewayResponse(false,
                    GatewayErrorCode.UPSTREAM_PARTIAL_FAILURE.name(), 207,
                    descriptor.resource(), descriptor.version(), result.body(),
                    notices, explain, result.attempts(), false);
            case TIMEOUT -> new GatewayResponse(false,
                    GatewayErrorCode.UPSTREAM_TIMEOUT.name(), 504,
                    descriptor.resource(), descriptor.version(), FALLBACK_BODY,
                    notices, explain, result.attempts(), true);
            case CIRCUIT_OPEN -> new GatewayResponse(false,
                    GatewayErrorCode.UPSTREAM_CIRCUIT_OPEN.name(), 503,
                    descriptor.resource(), descriptor.version(), FALLBACK_BODY,
                    notices, explain, result.attempts(), true);
            case UPSTREAM_ERROR -> new GatewayResponse(false,
                    GatewayErrorCode.UPSTREAM_ERROR.name(), 502,
                    descriptor.resource(), descriptor.version(), FALLBACK_BODY,
                    notices, explain, result.attempts(), true);
        };
    }

    private GatewayResponse reject(String resource, String version,
                                   GatewayErrorCode code, int httpStatus, String message) {
        return reject(resource, version, code, httpStatus, message,
                Map.of("reason", message), List.of());
    }

    private GatewayResponse reject(String resource, String version,
                                   GatewayErrorCode code, int httpStatus, String message,
                                   Map<String, Object> explain, List<String> notices) {
        log.info("[GATEWAY] reject: resource={} version={} code={} reason={}",
                resource, version, code, message);
        return new GatewayResponse(false, code.name(), httpStatus, resource, version,
                null, notices, explain, 0, false);
    }

    private static final String FALLBACK_BODY =
            "{\"degraded\":true,\"message\":\"temporarily unavailable; request not applied\"}";

    private static String mask(String apiKey) {
        if (apiKey == null) {
            return "<none>";
        }
        if (apiKey.length() <= 4) {
            return "****";
        }
        return apiKey.substring(0, 4) + "***";
    }
}
