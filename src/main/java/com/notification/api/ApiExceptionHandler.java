package com.notification.api;

import com.notification.api.dto.Problem;
import java.util.Comparator;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * T028 — turns validation failures into the structured rejection body (FR-004).
 *
 * <p>Every offending field is reported in one response. Jakarta Validation collects all violations
 * before this handler runs, which is why the constraints live on the DTO rather than as procedural
 * checks in the service — structural validation is what makes "name every field" the default
 * rather than something a developer has to remember.
 *
 * <p>Error messages carry field names and codes, never the offending values: a rejected content
 * payload or recipient reference must not be echoed back into logs through an error body
 * (Principle V).
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Problem.ValidationBody> onValidationFailure(MethodArgumentNotValidException ex) {
        List<Problem.FieldError> errors =
                ex.getBindingResult().getFieldErrors().stream()
                        .map(
                                fe ->
                                        new Problem.FieldError(
                                                fe.getField(),
                                                fe.getCode() == null ? "invalid" : fe.getCode(),
                                                fe.getDefaultMessage() == null
                                                        ? "is invalid"
                                                        : fe.getDefaultMessage()))
                        // Deterministic ordering so contract tests are not order-dependent.
                        .sorted(Comparator.comparing(Problem.FieldError::field))
                        .distinct()
                        .toList();

        List<Problem.FieldError> all = new java.util.ArrayList<>(errors);
        ex.getBindingResult().getGlobalErrors().stream()
                .map(
                        ge ->
                                new Problem.FieldError(
                                        ge.getObjectName(),
                                        ge.getCode() == null ? "invalid" : ge.getCode(),
                                        ge.getDefaultMessage() == null ? "is invalid" : ge.getDefaultMessage()))
                .forEach(all::add);

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(
                        new Problem.ValidationBody(
                                "about:blank",
                                "Notification rejected",
                                400,
                                "The submission was rejected. Every offending field is listed.",
                                all));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Problem.ValidationBody> onUnreadableBody(HttpMessageNotReadableException ex) {
        // Deliberately does not echo the parse detail: a malformed body may contain content.
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(
                        new Problem.ValidationBody(
                                "about:blank",
                                "Notification rejected",
                                400,
                                "The request body could not be parsed.",
                                List.of(new Problem.FieldError("body", "unreadable", "is not valid JSON"))));
    }

    @ExceptionHandler(NotificationNotFoundException.class)
    public ResponseEntity<Problem.Body> onNotFound(NotificationNotFoundException ex) {
        // FR-018: distinct from a known notification with no completed deliveries, which is a 200.
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(
                        new Problem.Body(
                                "about:blank", "Notification not found", 404, "No notification with that identity."));
    }
}
