package com.notification.contract;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers;
import com.notification.integration.SubmissionTestSupport;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** T048 — GET status conformance to contracts/openapi.yaml, 200 and 404 (Principle I). */
class NotificationStatusContractTest extends SubmissionTestSupport {

    private static final String SPEC = "specs/001-notification-management-core/contracts/openapi.yaml";

    @Test
    void statusResponseConformsToTheContract() throws Exception {
        UUID id = submitAndGetId("status-contract-1");

        mockMvc.perform(get("/api/v1/notifications/{id}/status", id))
                .andExpect(status().isOk())
                .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC));
    }

    @Test
    void notFoundResponseConformsToTheContract() throws Exception {
        mockMvc.perform(get("/api/v1/notifications/{id}/status", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC));
    }

    private UUID submitAndGetId(String clientId) throws Exception {
        String body =
                mockMvc.perform(
                                post("/api/v1/notifications")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(validSubmission(clientId)))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return UUID.fromString(body.replaceAll(".*\"id\"\\s*:\\s*\"([^\"]+)\".*", "$1"));
    }
}
