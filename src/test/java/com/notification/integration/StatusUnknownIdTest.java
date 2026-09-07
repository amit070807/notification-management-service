package com.notification.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * T051 — "no such notification" is distinguishable from "exists, nothing delivered yet" (FR-018).
 *
 * <p>The source does not address unknown identifiers. Conflating the two cases would make
 * debugging ambiguous: a caller could not tell a lost submission from a slow one.
 */
class StatusUnknownIdTest extends SubmissionTestSupport {

    @Autowired private JdbcClient jdbc;

    @Test
    void neverIssuedIdentityReturns404() throws Exception {
        mockMvc.perform(get("/api/v1/notifications/{id}/status", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void knownNotificationWithNothingDeliveredReturns200() throws Exception {
        // The contrast that gives the 404 its meaning.
        UUID id = submitAndGetId("unk-1");
        mockMvc.perform(get("/api/v1/notifications/{id}/status", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("ACCEPTED"));
    }

    @Test
    void aClientIdentifierIsNotAcceptedAsARetrievalKey() throws Exception {
        // Spec D4: the client identifier is descriptive, not identifying, and is not unique.
        // Passing one where a server identity belongs must not resolve to anything.
        mockMvc.perform(get("/api/v1/notifications/{id}/status", "unk-1"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void notFoundBodyRevealsNothingAboutOtherNotifications() throws Exception {
        String body =
                mockMvc.perform(get("/api/v1/notifications/{id}/status", UUID.randomUUID()))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(body).doesNotContain("user-").doesNotContain("payload");
    }

    @Test
    void notificationWithZeroSelectedChannelsStillReturns200() throws Exception {
        // FR-024/G-20: routing selecting nothing is a real outcome, not a missing notification.
        UUID id = submitAndGetId("unk-2");
        jdbc.sql("DELETE FROM delivery WHERE notification_id = ?").param(id).update();

        mockMvc.perform(get("/api/v1/notifications/{id}/status", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deliveries.length()").value(0));
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
