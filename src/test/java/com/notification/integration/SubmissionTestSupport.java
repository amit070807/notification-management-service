package com.notification.integration;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/** Shared MockMvc wiring plus request builders for the submission tests. */
@AutoConfigureMockMvc
public abstract class SubmissionTestSupport extends PostgresIntegrationTest {

    @Autowired protected MockMvc mockMvc;

    /** A well-formed submission. Callers mutate the returned JSON to build invalid variants. */
    protected static String validSubmission(String clientId) {
        return """
               {
                 "clientNotificationId": "%s",
                 "sourceSystem": "billing",
                 "correlationId": "corr-%s",
                 "notificationType": "ALERT",
                 "severity": "HIGH",
                 "priority": "NORMAL",
                 "recipients": ["user-1", "user-2"],
                 "requestedChannels": ["EMAIL", "SMS"],
                 "createdAt": "2026-09-07T10:00:00Z",
                 "content": { "body": "payload-%s", "mediaType": "text/plain" }
               }
               """
                .formatted(clientId, clientId, clientId);
    }
}
