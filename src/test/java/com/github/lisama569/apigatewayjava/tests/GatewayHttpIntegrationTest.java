package com.github.lisama569.apigatewayjava.tests;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 端到端 HTTP 测试：状态码、错误码信封、废弃提示头均与单测断言一致。
 */
@SpringBootTest
@AutoConfigureMockMvc
class GatewayHttpIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(GatewayHttpIntegrationTest.class);

    @Autowired
    private MockMvc mockMvc;

    @Test
    void happyPathReturns200() throws Exception {
        log.info("[HTTP 请求输入] GET orders/v2 X-Caller-Id=caller-a");
        mockMvc.perform(get("/api/gateway/orders/v2").header("X-Caller-Id", "caller-a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.errorCode").doesNotExist());
        log.info("[HTTP 上游结果] 200 OK");
    }

    @Test
    void deprecatedVersionReturns200WithNotice() throws Exception {
        mockMvc.perform(get("/api/gateway/orders/v0").header("X-Caller-Id", "caller-a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deprecated").value(true))
                .andExpect(jsonPath("$.deprecationNotice").isNotEmpty());
    }

    @Test
    void unknownVersion404() throws Exception {
        mockMvc.perform(get("/api/gateway/orders/v42").header("X-Caller-Id", "caller-a"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("VERSION_UNKNOWN"));
    }

    @Test
    void removedVersion410() throws Exception {
        mockMvc.perform(get("/api/gateway/orders/v-beta").header("X-Caller-Id", "caller-a"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.errorCode").value("VERSION_REMOVED"));
    }

    @Test
    void missingIdentity401() throws Exception {
        mockMvc.perform(get("/api/gateway/users/v1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("IDENTITY_INVALID"));
    }

    @Test
    void forbidden403() throws Exception {
        // caller-b 仅允许 orders/users，访问 payments -> 403
        mockMvc.perform(get("/api/gateway/payments/v1").header("X-Caller-Id", "caller-b"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("IDENTITY_FORBIDDEN"));
    }

    @Test
    void quotaExceeded429() throws Exception {
        // caller-c 配额为 3
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(get("/api/gateway/payments/v1").header("X-Caller-Id", "caller-c"))
                    .andExpect(status().isOk());
        }
        log.info("[HTTP 限流判定] caller-c 已用满 3 次配额");
        mockMvc.perform(get("/api/gateway/payments/v1").header("X-Caller-Id", "caller-c"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.errorCode").value("QUOTA_EXCEEDED"));
        log.info("[HTTP 限流判定] 第 4 次请求被拒绝 429");
    }

    @Test
    void postIsRoutedAsNonIdempotent() throws Exception {
        mockMvc.perform(post("/api/gateway/orders/v2")
                        .header("X-Caller-Id", "caller-a")
                        .content("\"order-payload\""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void batchAllSuccessExposesIndexedItems() throws Exception {
        mockMvc.perform(post("/api/gateway/orders/v2/batch")
                        .header("X-Caller-Id", "caller-a")
                        .content("3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.batchData.length()").value(3))
                .andExpect(jsonPath("$.batchData[0].index").value(0))
                .andExpect(jsonPath("$.batchData[0].success").value(true))
                .andExpect(jsonPath("$.batchData[1].data.resource").value("orders"));
    }

    @Test
    void batchPartialFailureExposesPerItemAttribution() throws Exception {
        // 前 1 条子请求故障（GET 幂等，2 次尝试均失败），其余成功 -> 207
        mockMvc.perform(post("/api/gateway/orders/v2/batch")
                        .header("X-Caller-Id", "caller-a")
                        .header("X-Demo-Fault", "ERROR_5XX:1")
                        .content("3"))
                .andExpect(status().isMultiStatus())
                .andExpect(jsonPath("$.errorCode").value("UPSTREAM_PARTIAL_FAILURE"))
                .andExpect(jsonPath("$.batchData.length()").value(3))
                .andExpect(jsonPath("$.batchData[0].success").value(false))
                .andExpect(jsonPath("$.batchData[0].errorCode").value("ERROR_5XX"))
                .andExpect(jsonPath("$.batchData[0].httpStatus").value(502))
                .andExpect(jsonPath("$.batchData[0].attempts").value(2))
                .andExpect(jsonPath("$.batchData[0].message").isNotEmpty())
                .andExpect(jsonPath("$.batchData[1].success").value(true))
                .andExpect(jsonPath("$.batchData[2].success").value(true));
    }

    @Test
    void batchAllFailedIs502WithEveryItemAttributed() throws Exception {
        mockMvc.perform(post("/api/gateway/orders/v2/batch")
                        .header("X-Caller-Id", "caller-a")
                        .header("X-Demo-Fault", "ERROR_5XX")
                        .content("2"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.errorCode").value("UPSTREAM_BATCH_FAILURE"))
                .andExpect(jsonPath("$.batchData.length()").value(2))
                .andExpect(jsonPath("$.batchData[0].errorCode").value("ERROR_5XX"))
                .andExpect(jsonPath("$.batchData[1].errorCode").value("ERROR_5XX"));
    }
}
