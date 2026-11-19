package io.github.dvchinx.countersign.core.model;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * A human's answer to an {@link ApprovalRequest}.
 *
 * <p>The approver's identity is recorded in the receipt, so the audit trail answers "who allowed it?". It should be
 * the authenticated identity of the person who answered, not a name they typed.
 */
public sealed interface ApprovalDecision permits ApprovalDecision.Approved, ApprovalDecision.Rejected {

    /**
     * Returns who answered.
     *
     * @return the approver's identity
     */
    String approver();

    /**
     * Approves a call.
     *
     * @param approver who approved it
     * @return the decision
     */
    static ApprovalDecision approved(String approver) {
        return new Approved(approver);
    }

    /**
     * Rejects a call.
     *
     * @param approver who rejected it
     * @param reason why, shown to the model; optional
     * @return the decision
     */
    static ApprovalDecision rejected(String approver, @Nullable String reason) {
        return new Rejected(approver, reason);
    }

    /**
     * The call may run.
     *
     * @param approver who approved it
     */
    record Approved(String approver) implements ApprovalDecision {

        /** Creates the decision. */
        public Approved {
            requireApprover(approver);
        }
    }

    /**
     * The call must not run.
     *
     * @param approver who rejected it
     * @param reason why, shown to the model but not stored in the receipt; may be {@code null}
     */
    record Rejected(String approver, @Nullable String reason) implements ApprovalDecision {

        /** Creates the decision. */
        public Rejected {
            requireApprover(approver);
        }
    }

    private static void requireApprover(String approver) {
        Objects.requireNonNull(approver, "approver");
        if (approver.isBlank()) {
            throw new IllegalArgumentException("approver must not be blank");
        }
    }
}
