package com.github.lisama569.apigatewayjava.tests;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every request must leave an explainable trace: request input, rate-limit decision,
 * upstream call result.
 */
class GatewayLoggingTests {

    @Test
    void logsRequestInputQuotaDecisionAndUpstreamResult() {
        GatewayTestHarness harness = new GatewayTestHarness();
        Logger logger = (Logger) LoggerFactory.getLogger("GATEWAY");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            harness.gateway().invoke("orders", "v3", "key-basic", "ok", "log-me");

            String all = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .reduce("", (a, b) -> a + "\n" + b);

            assertThat(all).contains("[GATEWAY] request input:");
            assertThat(all).contains("resource=orders");
            assertThat(all).contains("[GATEWAY] rate-limit decision:");
            assertThat(all).contains("caller=basic-caller");
            assertThat(all).contains("[GATEWAY] upstream result:");
            assertThat(all).contains("type=SUCCESS");
        } finally {
            logger.detachAppender(appender);
        }
    }
}
