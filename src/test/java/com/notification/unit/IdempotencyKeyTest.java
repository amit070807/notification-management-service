package com.notification.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.notification.domain.model.Channel;
import com.notification.domain.model.IdempotencyKey;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * T012 — the key that makes reclaim safe (FR-161, FR-163, ADR-019, spec D12).
 *
 * <p>The case the key exists for: a worker or provider dies after the provider call and before the
 * outcome is recorded. Reclaiming that delivery is necessary — otherwise it is stranded forever
 * (B-13) — but reclaiming it may repeat a call the provider already processed. The key is what makes
 * the repeat recognisable to the provider.
 *
 * <p>The load-bearing property is therefore <b>reproducibility after a crash that recorded
 * nothing</b>. Every component comes from a row written before the call, so the key can be derived
 * again with no additional state.
 */
class IdempotencyKeyTest {

    private static final UUID NOTIFICATION = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID RECIPIENT = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test
    void identicalInputsProduceIdenticalKeys() {
        assertThat(IdempotencyKey.of(NOTIFICATION, RECIPIENT, Channel.EMAIL, 1))
                .isEqualTo(IdempotencyKey.of(NOTIFICATION, RECIPIENT, Channel.EMAIL, 1));
    }

    @Test
    void reDerivationAfterACrashThatRecordedNothingReproducesTheKey() {
        // The whole point. Nothing was persisted between the two derivations, and they must match.
        IdempotencyKey beforeCrash = IdempotencyKey.of(NOTIFICATION, RECIPIENT, Channel.PUSH, 3);
        IdempotencyKey afterReclaim = IdempotencyKey.of(NOTIFICATION, RECIPIENT, Channel.PUSH, 3);

        assertThat(afterReclaim).isEqualTo(beforeCrash);
        assertThat(afterReclaim.value()).isEqualTo(beforeCrash.value());
    }

    @Test
    void aDifferentNotificationProducesADifferentKey() {
        assertThat(IdempotencyKey.of(UUID.randomUUID(), RECIPIENT, Channel.EMAIL, 1))
                .isNotEqualTo(IdempotencyKey.of(NOTIFICATION, RECIPIENT, Channel.EMAIL, 1));
    }

    @Test
    void aDifferentRecipientProducesADifferentKey() {
        // Without this, two recipients of one notification would share a key and a provider
        // honouring it would deliver to only one of them.
        assertThat(IdempotencyKey.of(NOTIFICATION, UUID.randomUUID(), Channel.EMAIL, 1))
                .isNotEqualTo(IdempotencyKey.of(NOTIFICATION, RECIPIENT, Channel.EMAIL, 1));
    }

    @Test
    void aDifferentChannelProducesADifferentKey() {
        // Same recipient on email and SMS are two sends, not one.
        assertThat(IdempotencyKey.of(NOTIFICATION, RECIPIENT, Channel.SMS, 1))
                .isNotEqualTo(IdempotencyKey.of(NOTIFICATION, RECIPIENT, Channel.EMAIL, 1));
    }

    @Test
    void aDifferentAttemptNumberProducesADifferentKey() {
        // A genuine retry after a genuine failure is a NEW send and must not be suppressed by the
        // provider. Only a re-attempt of the same attempt number repeats a key.
        assertThat(IdempotencyKey.of(NOTIFICATION, RECIPIENT, Channel.EMAIL, 2))
                .isNotEqualTo(IdempotencyKey.of(NOTIFICATION, RECIPIENT, Channel.EMAIL, 1));
    }

    @Test
    void theKeyIsOpaqueAndCarriesNoRecipientOrContent() {
        // It travels to a third-party provider, so it must not leak anything (Principle V).
        String value = IdempotencyKey.of(NOTIFICATION, RECIPIENT, Channel.EMAIL, 1).value();

        assertThat(value).doesNotContain(NOTIFICATION.toString());
        assertThat(value).doesNotContain(RECIPIENT.toString());
        assertThat(value).doesNotContain("EMAIL");
    }

    @Test
    void theKeyIsBoundedAndCodeShapedSoAnyProviderCanCarryIt() {
        String value = IdempotencyKey.of(NOTIFICATION, RECIPIENT, Channel.EMAIL, 1).value();

        assertThat(value).hasSizeLessThanOrEqualTo(64);
        assertThat(value).matches("^[A-Za-z0-9_.:-]+$");
    }

    @Test
    void anAttemptNumberBelowOneIsRejected() {
        // Attempt numbers start at 1. A zero would silently collide with nothing and mask a bug in
        // the caller's counting.
        assertThatThrownBy(() -> IdempotencyKey.of(NOTIFICATION, RECIPIENT, Channel.EMAIL, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullComponentsAreRejected() {
        assertThatThrownBy(() -> IdempotencyKey.of(null, RECIPIENT, Channel.EMAIL, 1))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> IdempotencyKey.of(NOTIFICATION, null, Channel.EMAIL, 1))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> IdempotencyKey.of(NOTIFICATION, RECIPIENT, null, 1))
                .isInstanceOf(NullPointerException.class);
    }
}
