package com.notification.api.dto;

import com.notification.domain.model.Channel;
import com.notification.domain.model.NotificationType;
import com.notification.domain.model.Priority;
import com.notification.domain.model.Severity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

/**
 * Submission payload, mirroring contracts/openapi.yaml.
 *
 * <p>Validation is declared here rather than performed in the service on purpose: Jakarta
 * Validation collects every violation before the handler runs, which is what makes FR-004's "name
 * every offending field" the default behaviour rather than something a developer must remember.
 *
 * <p>{@code recipients} is a list of opaque strings — the source defines no recipient field, so
 * none is requested (spec D5). {@code clientNotificationId} is descriptive, not identifying, and
 * carries no uniqueness requirement (FR-008a, D4).
 */
@ValidDeliveryWindow
public record SubmitNotificationRequest(
        @NotBlank @Size(max = 200) String clientNotificationId,
        @NotBlank @Size(max = 200) String sourceSystem,
        @NotBlank @Size(max = 200) String correlationId,
        @NotNull NotificationType notificationType,
        @NotNull Severity severity,
        @NotNull Priority priority,
        @NotEmpty(message = "at least one recipient is required")
                List<@NotBlank @Size(max = 200) String> recipients,
        @NotEmpty(message = "at least one requested channel is required") List<Channel> requestedChannels,
        @NotNull Instant createdAt,
        Instant notBefore,
        Instant expiresAt,
        @NotNull(message = "content is required") @Valid ContentPayloadDto content) {}
