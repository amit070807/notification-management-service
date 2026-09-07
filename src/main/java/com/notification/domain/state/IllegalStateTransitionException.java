package com.notification.domain.state;

/**
 * Raised when code attempts a transition the state machine does not permit.
 *
 * <p>Principle IV: an illegal transition must raise an error, never be silently applied.
 */
public class IllegalStateTransitionException extends RuntimeException {
    public IllegalStateTransitionException(Enum<?> from, Enum<?> to) {
        super("Illegal transition %s -> %s".formatted(from, to));
    }
}
