package com.notification.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.notification.domain.retry.RetryAuditRef;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * T063 — the reference that pairs a retry execution with the scheduling that caused it (FR-150a).
 *
 * <p>A pure function rather than a stored foreign key to the audit row. Reading the scheduling record
 * back to link it would mean the pairing could only be written after a successful read, so a reader
 * would lose the pairing in exactly the case it matters most: a run where something went wrong.
 *
 * <p>The derivation rests on one fact about the writer: the scheduling recorded after attempt N is
 * what schedules attempt N+1. So an execution of attempt N+1 refers to the scheduling stamped N.
 */
class RetryAuditRefTest {

    private static final UUID DELIVERY = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Test
    void anExecutionRefersToTheSchedulingOfThePrecedingAttempt() {
        assertThat(RetryAuditRef.forExecutionOfAttempt(DELIVERY, 2))
                .isEqualTo(RetryAuditRef.forSchedulingAfterAttempt(DELIVERY, 1));
    }

    @Test
    void consecutiveRetriesDoNotCollide() {
        // FR-150a is about SEVERAL retries. A ref that repeated across attempts would satisfy a
        // single-retry test and still leave the reader unable to pair anything.
        assertThat(RetryAuditRef.forExecutionOfAttempt(DELIVERY, 2))
                .isNotEqualTo(RetryAuditRef.forExecutionOfAttempt(DELIVERY, 3));
    }

    @Test
    void refsAreScopedToTheirDelivery() {
        assertThat(RetryAuditRef.forExecutionOfAttempt(DELIVERY, 2))
                .isNotEqualTo(RetryAuditRef.forExecutionOfAttempt(UUID.randomUUID(), 2));
    }

    @Test
    void theRefNamesTheDeliveryAndTheSchedulingAttempt() {
        assertThat(RetryAuditRef.forExecutionOfAttempt(DELIVERY, 4)).isEqualTo(DELIVERY + "#3");
    }

    @Test
    void aFirstAttemptHasNoSchedulingToReferTo() {
        // Attempt 1 was never scheduled by a retry, so producing a ref for it would invent a pairing
        // to a record that does not exist. Refusing is the point: the caller must not emit
        // RETRY_EXECUTED for a first attempt at all.
        assertThatThrownBy(() -> RetryAuditRef.forExecutionOfAttempt(DELIVERY, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("first attempt");
    }
}
