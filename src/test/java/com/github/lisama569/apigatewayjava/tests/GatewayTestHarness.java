package com.github.lisama569.apigatewayjava.tests;

import com.github.lisama569.apigatewayjava.auth.Authenticator;
import com.github.lisama569.apigatewayjava.auth.CallerDirectory;
import com.github.lisama569.apigatewayjava.core.GatewayService;
import com.github.lisama569.apigatewayjava.ratelimit.QuotaService;
import com.github.lisama569.apigatewayjava.resilience.CircuitBreaker;
import com.github.lisama569.apigatewayjava.resilience.ResilientUpstreamInvoker;
import com.github.lisama569.apigatewayjava.resilience.RetryPolicy;
import com.github.lisama569.apigatewayjava.upstream.ScriptedUpstreamClient;
import com.github.lisama569.apigatewayjava.upstream.UpstreamRouter;
import com.github.lisama569.apigatewayjava.version.VersionRegistry;

import java.util.Map;

/**
 * Builds the gateway object graph locally - no Spring context or network needed.
 */
final class GatewayTestHarness {

    private final ScriptedUpstreamClient upstream = new ScriptedUpstreamClient();
    private final CircuitBreaker breaker = new CircuitBreaker();
    private final QuotaService quotaService = new QuotaService();
    private final GatewayService gateway;

    GatewayTestHarness() {
        UpstreamRouter router = new UpstreamRouter(Map.of("orders-upstream", upstream));
        ResilientUpstreamInvoker invoker =
                new ResilientUpstreamInvoker(router, breaker, new RetryPolicy());
        this.gateway = new GatewayService(
                new VersionRegistry(),
                new Authenticator(new CallerDirectory()),
                quotaService,
                invoker);
    }

    GatewayService gateway() {
        return gateway;
    }

    ScriptedUpstreamClient upstream() {
        return upstream;
    }

    CircuitBreaker breaker() {
        return breaker;
    }

    QuotaService quotaService() {
        return quotaService;
    }

    void reset() {
        upstream.reset();
        breaker.reset("orders-upstream");
        quotaService.reset("key-premium");
        quotaService.reset("key-basic");
        quotaService.reset("key-zero");
    }
}
