package com.notification.api;

import com.notification.api.dto.SubmissionAcceptedResponse;
import com.notification.api.dto.SubmissionResponse;
import com.notification.api.dto.SubmissionSuppressed;
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
 * <p>An accepted submission returns 202 with a Location header, never 200: the work has been
 * accepted, not performed (FR-029). Hand-written per ADR-013, with conformance to
 * contracts/openapi.yaml asserted by the contract test rather than guaranteed by generation.
 *
 * <p>T056 — a <b>suppressed</b> submission returns 200 (FR-144, ADR-021). The status code is the
 * signal, deliberately: 202 would tell a caller its notification was accepted for processing when no
 * delivery exists, and a caller that ignores unfamiliar response fields — which is most of them —
 * would never learn otherwise. There is also no Location header, because there is no new resource to
 * point at.
 */
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final SubmissionService submissions;

    public NotificationController(SubmissionService submissions) {
        this.submissions = submissions;
    }

    @PostMapping
    public ResponseEntity<SubmissionResponse> submit(@Valid @RequestBody SubmitNotificationRequest request) {

        var outcome =
                submissions.submit(
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

        return switch (outcome) {
            case SubmissionService.Outcome.Accepted accepted ->
                    ResponseEntity.accepted()
                            .location(URI.create("/api/v1/notifications/" + accepted.id() + "/status"))
                            .body(
                                    new SubmissionAcceptedResponse(
                                            accepted.id(),
                                            accepted.clientNotificationId(),
                                            accepted.state(),
                                            accepted.receivedAt()));
            case SubmissionService.Outcome.Suppressed suppressed ->
                    ResponseEntity.ok(
                            SubmissionSuppressed.of(
                                    suppressed.originalNotificationId(),
                                    suppressed.clientNotificationId(),
                                    suppressed.suppressedAt()));
        };
    }
}
