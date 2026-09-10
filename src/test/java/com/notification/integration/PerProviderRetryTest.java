package com.notification.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.notification.config.RetryPolicySelector;
import com.notification.domain.model.Channel;
import com.notification.domain.retry.FailureClassification;
import com.notification.fixtures.MutableClock;
import com.notification.worker.DeliveryWorker;
import com.notification.worker.OutboxPoller;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

/**
 * T029 — a per-channel schedule is honoured, and is still bounded (FR-132, SC-108).
 *
 * <p>SMS is given a longer base delay than the default here. The assertion is not that the delay is
 * longer — {@code RetryPolicySelectorTest} covers the arithmetic — but that the <b>worker</b> uses the
 * per-channel policy rather than the global one, which is the wiring the unit test cannot reach.
 *
 * <p>The bound assertion matters more than the schedule one. A channel that retried a permanent
 * failure forever would still look plausible in configuration, and section 4.5 forbids it.
 */
@TestPropertySource(properties = {
    "notification.retry.overrides.SMS.base-delay=PT30S",
    "notification.retry.overrides.SMS.max-attempts=3"
})
class PerProviderRetryTest extends SubmissionTestSupport {

    @Autowired private OutboxPoller poller;
    @Autowired private DeliveryWorker worker;
    @Autowired private MutableClock clock;
    @Autowired private TestChannelConfig.Scripts scripts;
    @Autowired private RetryPolicySelector selector;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void theOverrideIsResolvedForTheChannelAndNotForOthers() {
        assertThat(selector.forChannel(Channel.SMS).baseDelay()).isEqualTo(Duration.ofSeconds(30));
        assertThat(selector.forChannel(Channel.SMS).maxAttempts()).isEqualTo(3);
        assertThat(selector.forChannel(Channel.EMAIL).baseDelay()).isEqualTo(Duration.ofSeconds(1));
        assertThat(selector.forChannel(Channel.EMAIL).maxAttempts()).isEqualTo(5);
    }

    @Test
    void theWorkerHonoursTheOverriddenBoundNotTheDefault() throws Exception {
        // The wiring assertion: SMS exhausts at 3, not at the default 5. If the worker were still
        // reading the global policy this would reach 5 and pass no differently in isolation.
        scripts.of(Channel.SMS).alwaysFail(FailureClassification.TRANSIENT_PROVIDER_FAILURE);

        UUID id = submitSmsOnly("per-retry-1");
        poller.drainOnce();
        for (int i = 0; i < 12; i++) {
            worker.runOnce();
            clock.advance(Duration.ofMinutes(5));
        }

        JsonNode delivery = statusOf(id).get("deliveries").get(0);
        assertThat(delivery.get("state").asText()).isEqualTo("EXHAUSTED");
        assertThat(delivery.get("attemptCount").asInt()).isEqualTo(3);
    }

    @Test
    void anotherChannelStillUsesItsOwnBound() throws Exception {
        scripts.of(Channel.EMAIL).alwaysFail(FailureClassification.TRANSIENT_PROVIDER_FAILURE);

        UUID id = submitEmailOnly("per-retry-2");
        poller.drainOnce();
        for (int i = 0; i < 12; i++) {
            worker.runOnce();
            clock.advance(Duration.ofMinutes(5));
        }

        assertThat(statusOf(id).get("deliveries").get(0).get("attemptCount").asInt()).isEqualTo(5);
    }

    private UUID submitSmsOnly(String clientId) throws Exception {
        return submit(clientId, "[\"SMS\"]");
    }

    private UUID submitEmailOnly(String clientId) throws Exception {
        return submit(clientId, "[\"EMAIL\"]");
    }

    private UUID submit(String clientId, String channels) throws Exception {
        String json =
                validSubmission(clientId)
                        .replace("[\"EMAIL\", \"SMS\"]", channels)
                        .replace("\"recipients\": [\"user-1\", \"user-2\"]", "\"recipients\": [\"user-1\"]");
        String body =
                mockMvc.perform(post("/api/v1/notifications").contentType(MediaType.APPLICATION_JSON).content(json))
                        .andExpect(status().isAccepted())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return UUID.fromString(body.replaceAll(".*\"id\"\\s*:\\s*\"([^\"]+)\".*", "$1"));
    }

    private JsonNode statusOf(UUID id) throws Exception {
        return mapper.readTree(
                mockMvc.perform(get("/api/v1/notifications/{id}/status", id))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString());
    }
}
