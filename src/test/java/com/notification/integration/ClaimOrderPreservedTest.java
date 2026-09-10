package com.notification.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.notification.domain.model.Channel;
import com.notification.domain.model.Severity;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * T017 — the batch is <b>processed</b> in claim order, not merely selected in it (FR-205, SC-205, B-05).
 *
 * <p>A separate test from {@link SeverityClaimOrderTest} for a specific reason. That test uses a batch
 * size of one, so selection order and processing order are the same thing by construction. This one
 * claims four deliveries in a <b>single</b> batch, which is where they can diverge: the claim is
 * {@code UPDATE … FROM candidate … RETURNING}, and SQL does not guarantee {@code RETURNING} preserves
 * the CTE's {@code ORDER BY}.
 *
 * <p>That is why FR-205 exists as its own requirement. An implementation that ordered the CTE and
 * trusted {@code RETURNING} would pass every ordering test written against a batch of one, ship, and
 * then start delivering in arbitrary order after an unrelated plan change — a regression with no code
 * change to attribute it to.
 *
 * <p>The assertion is on what the <b>provider</b> saw, because that is the only place the dispatch
 * sequence exists. The database records which attempts happened, not the order they were issued in, and
 * the test clock makes their timestamps tie.
 */
@TestPropertySource(properties = "notification.features.severity-claim-order=true")
class ClaimOrderPreservedTest extends SeverityClaimOrderSupport {

    @Test
    void theWorkerAttemptsTheBatchInRankOrder() throws Exception {
        scripts.of(Channel.EMAIL).alwaysSucceed();

        // Submitted so that age and rank disagree on every pair.
        submit("proc-low", Severity.LOW);
        submit("proc-medium", Severity.MEDIUM);
        submit("proc-high", Severity.HIGH);
        submit("proc-critical", Severity.CRITICAL);

        // Default batch size: all four claimed together, so this is about the order within one batch.
        worker.runOnce();

        assertThat(scripts.of(Channel.EMAIL).recipientsSeen().stream().map(r -> r.value()).toList())
                .as("the worker must attempt in claim order, not in RETURNING order (FR-205)")
                .containsExactly("proc-critical", "proc-high", "proc-medium", "proc-low");
    }

    @Test
    void oneBatchClaimsEveryDueDelivery() throws Exception {
        scripts.of(Channel.EMAIL).alwaysSucceed();
        submit("batch-a", Severity.LOW);
        submit("batch-b", Severity.CRITICAL);

        // Guards the setup of the test above rather than the feature: if the batch size were one, the
        // ordering assertion would silently become a selection-order assertion and FR-205 would go
        // untested while looking tested.
        //
        // Three, not two: the CRITICAL submission also produces an escalated SMS delivery (B-02).
        assertThat(worker.runOnce()).isEqualTo(3);
    }
}
