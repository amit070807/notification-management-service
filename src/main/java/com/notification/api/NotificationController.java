package com.notification.api;

import com.notification.api.dto.SubmissionAcceptedResponse;
import com.notification.api.dto.SubmitNotificationRequest;
import com.notification.application.SubmissionService;
import jakarta.validation.Valid;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * T046 — the submission endpoint.
 *
 * <p>Returns 202 with a Location header, never 200: the work has been accepted, not performed
 * (FR-029). Hand-written per ADR-013, with conformance to contracts/openapi.yaml asserted by the
 * contract test rather than guaranteed by generation.
 */
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final SubmissionService submissions;

    public NotificationController(SubmissionService submissions) {
        this.submissions = submissions;
    }

    @PostMapping
    public ResponseEntity<SubmissionAcceptedResponse> submit(@Valid @RequestBody SubmitNotificationRequest request) {

        var accepted =
                submissions.accept(
                        new SubmissionService.Command(
                                request.clientNotificationId(),
                                request.sourceSystem(),
                                request.correlationId(),
                                request.notificationType(),
                                request.severity(),
                                request.priority(),
                                request.recipients(),
                                request.requestedChannels(),
                                request.createdAt(),
                                request.notBefore(),
                                request.expiresAt(),
                                request.content().body().getBytes(StandardCharsets.UTF_8)));

        return ResponseEntity.accepted()
                .location(URI.create("/api/v1/notifications/" + accepted.id() + "/status"))
                .body(
                        new SubmissionAcceptedResponse(
                                accepted.id(),
                                accepted.clientNotificationId(),
                                accepted.state(),
                                accepted.receivedAt()));
    }
}
