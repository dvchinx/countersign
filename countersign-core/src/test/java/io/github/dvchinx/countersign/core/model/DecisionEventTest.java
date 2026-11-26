package io.github.dvchinx.countersign.core.model;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DecisionEventTest {

    private static DecisionEvent withoutReceipt(boolean proceeds, boolean auditFailed, Duration evaluationTime) {
        return new DecisionEvent(
                "tool",
                Decision.Type.ALLOW,
                EvaluationResult.Basis.POLICY_MATCHED,
                "p",
                null,
                proceeds,
                evaluationTime,
                null,
                auditFailed);
    }

    @Test
    void neverReportsACallThatProceedsWithoutAReceipt() {
        assertThatThrownBy(() -> withoutReceipt(true, false, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("a call never proceeds without a receipt");
    }

    @Test
    void neverReportsAnAuditFailureWithoutAReceipt() {
        assertThatThrownBy(() -> withoutReceipt(false, true, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsANegativeEvaluationTime() {
        assertThatThrownBy(() -> withoutReceipt(false, false, Duration.ofMillis(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void neverReportsRedactionsForACallThatDoesNotRun() {
        assertThatThrownBy(() -> new DecisionEvent(
                        "tool",
                        Decision.Type.DENY,
                        EvaluationResult.Basis.POLICY_MATCHED,
                        "p",
                        null,
                        false,
                        Duration.ZERO,
                        null,
                        false,
                        List.of(new Redaction("no-cards", Set.of(Redaction.Target.RESULT)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("redactions apply only to a call that runs");
    }
}
