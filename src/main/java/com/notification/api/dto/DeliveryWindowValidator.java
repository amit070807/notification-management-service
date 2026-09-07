package com.notification.api.dto;

import com.notification.domain.port.ClockPort;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * Enforces FR-003d and FR-003e.
 *
 * <p>Both rules exist only because spec D3 resolved source 4.1's single "scheduling/expiration
 * timestamp" into two independently optional fields. Under the single-field reading neither would
 * be expressible.
 *
 * <p>Violations are attached to a specific property node so the caller gets a field name rather
 * than an object-level error.
 */
public class DeliveryWindowValidator
        implements ConstraintValidator<ValidDeliveryWindow, SubmitNotificationRequest> {

    private final ClockPort clock;

    public DeliveryWindowValidator(ClockPort clock) {
        this.clock = clock;
    }

    @Override
    public boolean isValid(SubmitNotificationRequest req, ConstraintValidatorContext ctx) {
        if (req == null) {
            return true;
        }
        boolean valid = true;
        ctx.disableDefaultConstraintViolation();

        // FR-003d: a window that can never open.
        if (req.notBefore() != null && req.expiresAt() != null && !req.notBefore().isBefore(req.expiresAt())) {
            ctx.buildConstraintViolationWithTemplate("notBefore must be before expiresAt")
                    .addPropertyNode("notBefore")
                    .addConstraintViolation();
            valid = false;
        }

        // FR-003e: rejected at submission rather than accepted and immediately expired, which
        // would make the EXPIRED terminal state ambiguous between "expired in flight" and
        // "expired on arrival".
        if (req.expiresAt() != null && !req.expiresAt().isAfter(clock.now())) {
            ctx.buildConstraintViolationWithTemplate("expiresAt has already passed")
                    .addPropertyNode("expiresAt")
                    .addConstraintViolation();
            valid = false;
        }
        return valid;
    }
}
