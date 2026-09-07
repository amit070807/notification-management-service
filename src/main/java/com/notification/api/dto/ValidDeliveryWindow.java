package com.notification.api.dto;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Class-level constraint for the two rules that involve more than one field, or the clock.
 *
 * <p>Kept as a constraint rather than a service-side check so that a submission failing both a
 * field rule and a window rule still produces ONE response naming both (FR-004).
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = DeliveryWindowValidator.class)
public @interface ValidDeliveryWindow {
    String message() default "invalid delivery window";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
