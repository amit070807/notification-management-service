package com.notification.integration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.notification.fixtures.MutableClock;
import com.notification.worker.DeliveryWorker;
import com.notification.worker.OutboxPoller;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/** Shared plumbing for the retry tests: submit on one channel, then drive the worker. */
public abstract class RetryTestSupport extends SubmissionTestSupport {

    @Autowired protected OutboxPoller poller;
    @Autowired protected DeliveryWorker worker;
    @Autowired protected MutableClock clock;
    @Autowired protected TestChannelConfig.Scripts scripts;
    protected final ObjectMapper mapper = new ObjectMapper();

    /** EMAIL only, so a test drives exactly one delivery and assertions stay unambiguous. */
    protected UUID submitEmailOnly(String clientId) throws Exception {
        String json = validSubmission(clientId).replace("[\"EMAIL\", \"SMS\"]", "[\"EMAIL\"]")
                .replace("\"recipients\": [\"user-1\", \"user-2\"]", "\"recipients\": [\"user-1\"]");
        String body =
                mockMvc.perform(post("/api/v1/notifications").contentType(MediaType.APPLICATION_JSON).content(json))
                        .andExpect(status().isAccepted())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return UUID.fromString(body.replaceAll(".*\"id\"\\s*:\\s*\"([^\"]+)\".*", "$1"));
    }

    /** Runs the worker repeatedly, advancing the clock past each backoff window. */
    protected void runUntilSettled(int maxRounds) {
        for (int i = 0; i < maxRounds; i++) {
            worker.runOnce();
            clock.advance(Duration.ofMinutes(5)); // well past any backoff ceiling
        }
    }

    protected JsonNode statusOf(UUID id) throws Exception {
        return mapper.readTree(
                mockMvc.perform(get("/api/v1/notifications/{id}/status", id))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString());
    }

    protected JsonNode firstDelivery(UUID id) throws Exception {
        return statusOf(id).get("deliveries").get(0);
    }
}
