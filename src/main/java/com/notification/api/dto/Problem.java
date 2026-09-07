package com.notification.api.dto;

import java.util.List;

/** Error bodies matching contracts/openapi.yaml. */
public final class Problem {

    private Problem() {}

    public record Body(String type, String title, int status, String detail) {}

    /**
     * FR-004: a rejection must name EVERY offending field, not just the first one encountered.
     * Returning one error at a time turns a single malformed submission into a guessing game for
     * the caller.
     */
    public record ValidationBody(String type, String title, int status, String detail, List<FieldError> errors) {}

    public record FieldError(String field, String code, String message) {}
}
