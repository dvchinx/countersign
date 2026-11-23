package io.github.dvchinx.countersign.core.model;

import java.time.Duration;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * What happened to one tool call, reported to a {@code DecisionListener} after the decision path has finished and
 * before the tool runs.
 *
 * <p>Made for metrics, tracing and alerting. It carries the decision and how it was reached, but never the call's
 * arguments, so a listener cannot log them by accident. Who the agent acted for and the conversation are in the
 * {@link #receipt()} when one was recorded.
 *
 * @param tool the tool's name
 * @param decision what the policies decided
 * @param basis why the decision was reached
 * @param policyId the policy that decided, or {@code null} when the default decision applied or the arguments could
 *     not be read
 * @param approval what happened to the approval, for {@link Decision.Type#REQUIRE_APPROVAL}; otherwise {@code null}
 * @param proceeds whether the tool runs
 * @param evaluationTime time spent evaluating policies; zero when they were not evaluated. Excludes waiting for
 *     approval, signing and auditing
 * @param receipt the receipt recorded for the call, or {@code null} if it could not be created (the call is then
 *     refused)
 * @param auditFailed whether storing the receipt in the audit sink failed
 */
public record DecisionEvent(
        String tool,
        Decision.Type decision,
        EvaluationResult.Basis basis,
        @Nullable String policyId,
        @Nullable ApprovalOutcome approval,
        boolean proceeds,
        Duration evaluationTime,
        @Nullable Receipt receipt,
        boolean auditFailed) {

    /**
     * Creates an event.
     *
     * @throws IllegalArgumentException if the call proceeds without a receipt, an audit failure is reported without
     *     a receipt, or the evaluation time is negative
     */
    public DecisionEvent {
        Objects.requireNonNull(tool, "tool");
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(basis, "basis");
        Objects.requireNonNull(evaluationTime, "evaluationTime");
        if (evaluationTime.isNegative()) {
            throw new IllegalArgumentException("evaluationTime must not be negative");
        }
        if (receipt == null && proceeds) {
            throw new IllegalArgumentException("a call never proceeds without a receipt");
        }
        if (receipt == null && auditFailed) {
            throw new IllegalArgumentException("only a recorded receipt can fail to be stored");
        }
    }

    /**
     * Returns whether a receipt was recorded for the call.
     *
     * @return {@code false} if hashing or signing the receipt failed
     */
    public boolean receiptRecorded() {
        return receipt != null;
    }
}
