package io.github.dvchinx.countersign.core.model;

import java.util.Objects;

/**
 * What Countersign decided about one tool call.
 *
 * <p>Sealed, so that code applying a decision uses an exhaustive {@code switch} and the compiler flags every place
 * to update when a new kind of decision is added. Reasons are shown to the model as part of a refusal, so they must
 * explain the rule, never echo argument values.
 *
 * <p>{@code REDACT} is planned but not part of the {@code 0.1.0} scope yet.
 */
public sealed interface Decision permits Decision.Allow, Decision.Deny, Decision.RequireApproval {

    /**
     * Returns the kind of decision, for metrics tags, receipts and configuration.
     *
     * @return the kind of decision
     */
    Type type();

    /**
     * Returns the decision that lets the tool run.
     *
     * @return the allow decision
     */
    static Decision allow() {
        return Allow.INSTANCE;
    }

    /**
     * Returns a decision that blocks the tool.
     *
     * @param reason why the call is blocked, shown to the model
     * @return the deny decision
     */
    static Decision deny(String reason) {
        return new Deny(reason);
    }

    /**
     * Returns a decision that runs the tool only after a human approves it.
     *
     * @param reason why approval is needed, shown to the approver
     * @return the require-approval decision
     */
    static Decision requireApproval(String reason) {
        return new RequireApproval(reason);
    }

    /** The kinds of decision, as they appear in receipts, metrics and configuration. */
    enum Type {
        /** The tool runs. */
        ALLOW,
        /** The tool does not run; the model receives a refusal. */
        DENY,
        /** The tool runs only if a human approves it in time. */
        REQUIRE_APPROVAL
    }

    /** The tool runs. */
    record Allow() implements Decision {

        private static final Allow INSTANCE = new Allow();

        @Override
        public Type type() {
            return Type.ALLOW;
        }
    }

    /**
     * The tool does not run.
     *
     * @param reason why the call is blocked, shown to the model
     */
    record Deny(String reason) implements Decision {

        /**
         * Creates a deny decision.
         *
         * @param reason why the call is blocked; must not be blank, so the model can explain the refusal
         */
        public Deny {
            requireReason(reason);
        }

        @Override
        public Type type() {
            return Type.DENY;
        }
    }

    /**
     * The tool runs only if a human approves it before the approval times out.
     *
     * @param reason why approval is needed, shown to the approver
     */
    record RequireApproval(String reason) implements Decision {

        /**
         * Creates a require-approval decision.
         *
         * @param reason why approval is needed; must not be blank, so the approver knows what to check
         */
        public RequireApproval {
            requireReason(reason);
        }

        @Override
        public Type type() {
            return Type.REQUIRE_APPROVAL;
        }
    }

    private static void requireReason(String reason) {
        Objects.requireNonNull(reason, "reason");
        if (reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
    }
}
