package com.github.lisama569.apigatewayjava.tests;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Black-box HTTP checks against the real Spring MVC entry point (no external services).
 */
@SpringBootTest
@AutoConfigureMockMvc
class GatewayHttpTests {

    @Autowired
    private MockMvc mvc;

    @Test
    void activeVersionReturnsOk() throws Exception {
        mvc.perform(post("/gateway/orders/v3")
                        .header("X-Api-Key", "key-premium")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("hello"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.resource").value("orders"))
                .andExpect(jsonPath("$.version").value("v3"));
    }

    @Test
    void deprecatedVersionCarriesNotice() throws Exception {
        mvc.perform(post("/gateway/orders/v2").header("X-Api-Key", "key-premium"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notices[0]").value(org.hamcrest.Matchers.containsString("DEPRECATED")));
    }

    @Test
    void removedVersionIs410() throws Exception {
        mvc.perform(post("/gateway/orders/v1").header("X-Api-Key", "key-premium"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("VERSION_REMOVED"));
    }

    @Test
    void unknownVersionIs404() throws Exception {
        mvc.perform(post("/gateway/orders/v77").header("X-Api-Key", "key-premium"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("VERSION_UNKNOWN"));
    }

    @Test
    void missingKeyIs401() throws Exception {
        mvc.perform(post("/gateway/orders/v3"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_INVALID_IDENTITY"));
    }

    @Test
    void forbiddenIs403() throws Exception {
        mvc.perform(post("/gateway/payments/v1").header("X-Api-Key", "key-basic"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
    }
}
