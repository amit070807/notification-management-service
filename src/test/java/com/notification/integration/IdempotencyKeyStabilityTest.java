package com.notification.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.notification.domain.model.Channel;
import com.notification.domain.model.IdempotencyKey;
import com.notification.domain.retry.FailureClassification;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * T038 — the idempotency key a re-attempt carries (FR-161, FR-163).
 *
 * <p>Two distinct requirements, and conflating them would defeat both:
 *
 * <ul>
 *   <li>A <b>repeat of the same logical attempt</b> — a reclaim after a crash — must carry the SAME
 *       key, because that is the only thing that lets the provider recognise the second call as the
 *       first one again (spec D12).
 *   <li>A <b>retry</b> is a NEW attempt and must carry a DIFFERENT key. Reusing it would have the
 *       provider suppress a legitimate retry as a duplicate, converting every transient failure into a
 *       permanent one — a worse outcome than the duplicate the key exists to prevent.
 * </ul>
 *
 * <p>The key is never stored (FR-163). What this asserts is that it is <i>derivable</i> from rows that
 * are: the delivery and its attempt number. That is what makes it survive the crash window, since a
 * key held only in memory would be gone exactly when it was needed.
 */
@TestPropertySource(properties = "notification.features.delivery-reclaim=true")
class IdempotencyKeyStabilityTest extends ReclaimTestSupport {

    @Test
    void aReclaimedReattemptRepeatsTheKeyOfTheAttemptItResumes() throws Exception {
        UUID notificationId = submitEmailOnly("key-stable");
        poller.drainOnce();
        UUID deliveryId = strandMidAttempt(notificationId);
        scripts.of(Channel.EMAIL).alwaysSucceed();

        worker.runOnce();

        IdempotencyKey expected =
                IdempotencyKey.of(notificationId, recipientIdOf(deliveryId), Channel.EMAIL, 1);

        // Two calls, because the delivery was stranded by a crash that happened AFTER the first one —
        // that is what makes it stranded. Both must carry the same key: the provider is being asked to
        // recognise a repeat of one logical attempt, which is the whole basis of the D12 agreement
        // (FR-161). Asserting a single call here would have been asserting that no crash occurred.
        assertThat(scripts.of(Channel.EMAIL).keysSeen()).containsExactly(expected, expected);
    }

    @Test
    void aRetryCarriesADifferentKeyBecauseItIsADifferentAttempt() throws Exception {
        UUID notificationId = submitEmailOnly("key-retry");
        poller.drainOnce();
        scripts.of(Channel.EMAIL)
                .thenFail(FailureClassification.TRANSIENT_PROVIDER_FAILURE)
                .thenSucceed();

        runUntilSettled(3);

        List<IdempotencyKey> keys = scripts.of(Channel.EMAIL).keysSeen();
        assertThat(keys).hasSize(2);
        assertThat(keys.get(0)).isNotEqualTo(keys.get(1));
    }

    @Test
    void theKeyIsNotStoredAnywhere() throws Exception {
        UUID notificationId = submitEmailOnly("key-unstored");
        poller.drainOnce();
        worker.runOnce();

        String value = scripts.of(Channel.EMAIL).keysSeen().get(0).value();

        // FR-163. Storing it would add a second place the key lives, and a stored copy that disagreed
        // with the derivation would be silently authoritative.
        assertThat(anyColumnContains(value)).isFalse();
    }

    private boolean anyColumnContains(String needle) {
        Integer hits =
                jdbc.sql(
                                """
                                SELECT (
                                    (SELECT count(*) FROM delivery_attempt
                                       WHERE coalesce(diagnostic, '') LIKE :needle)
                                  + (SELECT count(*) FROM audit_event
                                       WHERE payload::text LIKE :needle)
                                )
                                """)
                        .param("needle", "%" + needle + "%")
                        .query(Integer.class)
                        .single();
        return hits != null && hits > 0;
    }
}
