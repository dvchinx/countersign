package io.github.dvchinx.countersign.core.model;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The outcome of evaluating one invocation: the decision, and why it was reached.
 *
 * <p>Keeping the basis next to the decision lets receipts and metrics tell apart a call denied by a policy, a call
 * denied because no policy matched, and a call denied because a policy failed. All three block the tool, but they
 * call for different fixes.
 *
 * @param decision what to do with the call
 * @param basis why this decision was reached
 * @param policyId the policy that produced the decision; {@code null} when the default decision applied or the
 *     arguments could not be read
 */
public record EvaluationResult(
        Decision decision, Basis basis, @Nullable String policyId) {

    /** Why a decision was reached. */
    public enum Basis {
        /** A policy matched the invocation and its decision applies. */
        POLICY_MATCHED,
        /** No policy matched, so the engine's default decision applies. */
        DEFAULT_DECISION,
        /** A policy's condition failed while being evaluated, so the call is denied (fail closed). */
        EVALUATION_FAILED,
        /**
         * The tool input could not be read as a JSON object, so no policy could be evaluated and the call is denied
         * (fail closed). The invocation is recorded with empty arguments.
         */
        INVALID_ARGUMENTS
    }

    /**
     * Creates a result, checking that the parts are consistent.
     *
     * @throws IllegalArgumentException if a policy id is missing or present where it should not be, or if a failed
     *     evaluation or invalid arguments do not deny
     */
    public EvaluationResult {
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(basis, "basis");
        boolean policyDecided = basis == Basis.POLICY_MATCHED || basis == Basis.EVALUATION_FAILED;
        if (policyDecided != (policyId != null)) {
            throw new IllegalArgumentException("policyId is required exactly when a policy decided");
        }
        if ((basis == Basis.EVALUATION_FAILED || basis == Basis.INVALID_ARGUMENTS)
                && !(decision instanceof Decision.Deny)) {
            throw new IllegalArgumentException(basis + " must deny");
        }
    }

    /**
     * A policy matched.
     *
     * @param policyId the id of the matching policy
     * @param decision the policy's decision
     * @return the result
     */
    public static EvaluationResult matched(String policyId, Decision decision) {
        return new EvaluationResult(decision, Basis.POLICY_MATCHED, policyId);
    }

    /**
     * No policy matched.
     *
     * @param defaultDecision the engine's default decision
     * @return the result
     */
    public static EvaluationResult defaultDecision(Decision defaultDecision) {
        return new EvaluationResult(defaultDecision, Basis.DEFAULT_DECISION, null);
    }

    /**
     * A policy failed while being evaluated; the call is denied.
     *
     * @param policyId the id of the failing policy
     * @return the result, always a {@link Decision.Deny}
     */
    public static EvaluationResult failed(String policyId) {
        return new EvaluationResult(
                Decision.deny("The call was blocked because a policy could not be evaluated"),
                Basis.EVALUATION_FAILED,
                policyId);
    }

    /**
     * The tool input could not be read; the call is denied.
     *
     * @return the result, always a {@link Decision.Deny}
     */
    public static EvaluationResult invalidArguments() {
        return new EvaluationResult(
                Decision.deny("The call was blocked because its arguments are not a valid JSON object"),
                Basis.INVALID_ARGUMENTS,
                null);
    }
}
