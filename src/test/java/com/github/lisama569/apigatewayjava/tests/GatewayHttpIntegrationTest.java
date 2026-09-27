package com.github.lisama569.apigatewayjava.tests;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import com.github.lisama569.apigatewayjava.identity.InMemoryCallerDirectory;

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

    @Autowired
    private InMemoryCallerDirectory callerDirectory;

    @org.junit.jupiter.api.BeforeEach
    void registerDedicatedBatchCaller() {
        // 两个专用调用方：各有独立配额窗口，避免与既有 caller-c 用例及彼此之间相互影响
        callerDirectory.register("caller-batch-ok",
                java.util.Set.of("payments", "orders"), 3);
        callerDirectory.register("caller-batch-quota",
                java.util.Set.of("payments", "orders"), 3);
    }

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
    void batchAllSuccessReportsAllSuccessEnvelope() throws Exception {
        // caller-batch-ok 配额 3：3 个子请求按 3 计费（orders）
        mockMvc.perform(post("/api/gateway/orders/v2/batch")
                        .header("X-Caller-Id", "caller-batch-ok")
                        .content("3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.batchStatus").value("ALL_SUCCESS"))
                .andExpect(jsonPath("$.batchTotal").value(3))
                .andExpect(jsonPath("$.batchSucceeded").value(3))
                .andExpect(jsonPath("$.batchFailed").value(0))
                .andExpect(jsonPath("$.batchItems.length()").value(3));
        log.info("[HTTP 批量] ALL_SUCCESS 信封：batchStatus/计数/逐条 items 齐备");
    }

    @Test
    void batchQuotaIsChargedBySubRequestCount() throws Exception {
        // caller-batch-quota 配额 3：一批 3 个子请求恰好打满；第二批即使只有 2 个也整笔 429
        mockMvc.perform(post("/api/gateway/payments/v1/batch")
                        .header("X-Caller-Id", "caller-batch-quota")
                        .content("3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchStatus").value("ALL_SUCCESS"));
        mockMvc.perform(post("/api/gateway/payments/v1/batch")
                        .header("X-Caller-Id", "caller-batch-quota")
                        .content("2"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.errorCode").value("QUOTA_EXCEEDED"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("整批拒绝")));
        log.info("[HTTP 批量计费] 3 个子请求消耗 3 额度，后续整批 429 且账目一致");
    }
}
