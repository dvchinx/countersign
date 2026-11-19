package io.github.dvchinx.countersign.core.policy;

import io.github.dvchinx.countersign.core.model.Decision;
import io.github.dvchinx.countersign.core.model.EvaluationResult;
import io.github.dvchinx.countersign.core.model.ToolInvocation;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Decides what to do with a tool call by checking it against an ordered list of policies.
 *
 * <p>Rules, in order:
 *
 * <ol>
 *   <li>Policies are checked in ascending {@linkplain Policy#priority() priority}; ties keep the order given.
 *   <li>A policy applies when one of its tool patterns matches the tool name and its condition holds. The first
 *       policy that applies decides; later ones are not evaluated.
 *   <li>If no policy applies, the default decision applies. It should be a deny (fail closed); that is what the
 *       Spring Boot starter configures unless told otherwise.
 *   <li>If a condition throws, the call is denied at once with {@link EvaluationResult.Basis#EVALUATION_FAILED}.
 *       Skipping the broken policy instead could let a later, more permissive policy allow a call it was meant to
 *       block.
 * </ol>
 *
 * <p>The engine is immutable and thread-safe, never blocks on I/O, and never throws from {@link #evaluate}.
 */
public final class PolicyEngine {

    private static final System.Logger LOG = System.getLogger(PolicyEngine.class.getName());

    private final List<Policy> policies;
    private final Decision defaultDecision;

    /**
     * Creates an engine.
     *
     * @param policies the policies, in any order; they are sorted by priority, keeping the given order for ties
     * @param defaultDecision the decision when no policy applies; use a deny to fail closed
     * @throws InvalidPolicyException if two policies share an id
     */
    public PolicyEngine(List<Policy> policies, Decision defaultDecision) {
        Objects.requireNonNull(policies, "policies");
        this.defaultDecision = Objects.requireNonNull(defaultDecision, "defaultDecision");
        Set<String> ids = new HashSet<>();
        for (Policy policy : policies) {
            if (!ids.add(policy.id())) {
                throw new InvalidPolicyException("Duplicate policy id: '" + policy.id() + "'");
            }
        }
        // Stream.sorted is stable on an ordered stream, so policies with the same priority keep the order they were
        // given in.
        this.policies = policies.stream()
                .sorted(Comparator.comparingInt(Policy::priority))
                .toList();
    }

    /**
     * Decides what to do with an invocation.
     *
     * @param invocation the invocation to evaluate
     * @return the decision and why it was reached; never {@code null}, never throws for a failing condition
     */
    public EvaluationResult evaluate(ToolInvocation invocation) {
        Objects.requireNonNull(invocation, "invocation");
        for (Policy policy : policies) {
            if (!policy.targets(invocation.toolName())) {
                continue;
            }
            boolean applies;
            try {
                applies = policy.condition().matches(invocation);
            } catch (RuntimeException e) {
                // The exception type is safe to log; the message might quote argument values, so it goes to DEBUG.
                LOG.log(
                        System.Logger.Level.WARNING,
                        "Policy ''{0}'' failed on tool ''{1}'' ({2}); denying the call",
                        policy.id(),
                        invocation.toolName(),
                        e.getClass().getName());
                LOG.log(System.Logger.Level.DEBUG, "Policy '" + policy.id() + "' failure details", e);
                return EvaluationResult.failed(policy.id());
            }
            if (applies) {
                return EvaluationResult.matched(policy.id(), policy.decision());
            }
        }
        return EvaluationResult.defaultDecision(defaultDecision);
    }

    /**
     * Returns the policies in evaluation order.
     *
     * @return an unmodifiable list, sorted by priority
     */
    public List<Policy> policies() {
        return policies;
    }

    /**
     * Returns the decision used when no policy applies.
     *
     * @return the default decision
     */
    public Decision defaultDecision() {
        return defaultDecision;
    }
}
