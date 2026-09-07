package com.notification.contract;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers;
import com.atlassian.oai.validator.report.LevelResolver;
import com.atlassian.oai.validator.report.ValidationReport;
import com.notification.integration.SubmissionTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * T032 — conformance to contracts/openapi.yaml (Principle I).
 *
 * <p>ADR-013 chose hand-written controllers over generated interfaces, which makes this test the
 * mechanism that keeps the contract authoritative. Drift fails the build.
 */
class SubmitNotificationContractTest extends SubmissionTestSupport {

    private static final String SPEC = "specs/001-notification-management-core/contracts/openapi.yaml";

    /**
     * For rejection cases the request is invalid <em>on purpose</em> — that is what provokes the
     * 400 under test. Validating it against the contract would always fail and would assert
     * nothing useful, so request-level findings are ignored here and the response is what must
     * conform. Response validation stays at ERROR.
     */
    private static final OpenApiInteractionValidator RESPONSE_ONLY =
            OpenApiInteractionValidator.createFor(SPEC)
                    .withLevelResolver(
                            LevelResolver.create()
                                    .withLevel("validation.request", ValidationReport.Level.IGNORE)
                                    .build())
                    .build();

    @Test
    void acceptedSubmissionConformsToTheContract() throws Exception {
        // Both directions validated: a well-formed request must satisfy the contract too.
        mockMvc.perform(
                        post("/api/v1/notifications")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(validSubmission("contract-1")))
                .andExpect(status().isAccepted())
                .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC));
    }

    @Test
    void rejectedSubmissionConformsToTheContract() throws Exception {
        String json = validSubmission("contract-2").replace("[\"user-1\", \"user-2\"]", "[]");
        mockMvc.perform(post("/api/v1/notifications").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isBadRequest())
                .andExpect(OpenApiValidationMatchers.openApi().isValid(RESPONSE_ONLY));
    }
}
