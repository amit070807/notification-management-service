package com.notification.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The opaque content payload (spec D1).
 *
 * <p>Never parsed, rendered or transformed — only measured and stored. FR-058 makes it mandatory,
 * which is an owner decision rather than source text: source 4.1 lists no content field at all.
 */
public record ContentPayloadDto(
        @NotBlank(message = "content body is required and must not be empty")
        @Size(max = 65536, message = "content body exceeds the maximum payload size")
        String body,
        @Size(max = 100) String mediaType) {}
