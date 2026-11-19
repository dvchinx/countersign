package io.github.dvchinx.countersign.core.policy;

import io.github.dvchinx.countersign.core.model.Decision;
import io.github.dvchinx.countersign.core.model.EvaluationResult;
import io.github.dvchinx.countersign.core.model.ToolInvocation;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * Decides what to do with a tool call by checking it against an ordered list of policies, then against mandatory
 * policies.
 *
 * <p>Rules, in order:
 *
 * <ol>
 *   <li>Ordered policies are checked in ascending {@linkplain Policy#priority() priority}; ties keep the order given.
 *       A policy applies when one of its tool patterns matches the tool name and its condition holds. The first
 *       policy that applies decides; later ones are not evaluated.
 *   <li>Mandatory policies, such as those declared with {@code @RequiresApproval} on a tool method, are then checked.
 *       All of them are evaluated, and they can only make the outcome stricter, where {@code ALLOW} is less strict
 *       than {@code REQUIRE_APPROVAL}, which is less strict than {@code DENY}. A configured {@code allow} therefore
 *       cannot skip a mandatory approval, and a configured {@code deny} still wins over it. On a tie, the ordered
 *       policy's decision and reason are kept.
 *   <li>If no ordered policy applies, the strictest matching mandatory policy decides; if none matches either, the
 *       default decision applies. A tool that its own code declares as needing approval is therefore usable with
 *       approval even under a default {@code deny}.
 *   <li>If any condition throws, the call is denied at once with {@link EvaluationResult.Basis#EVALUATION_FAILED}.
 *       Skipping the broken policy instead could let a more permissive one allow a call it was meant to block.
 * </ol>
 *
 * <p>The engine is immutable and thread-safe, never blocks on I/O, and never throws from {@link #evaluate}.
 */
public final class PolicyEngine {

    private static final System.Logger LOG = System.getLogger(PolicyEngine.class.getName());

    private final List<Policy> policies;
    private final List<Policy> mandatoryPolicies;
    private final Decision defaultDecision;

    /**
     * Creates an engine with ordered policies only.
     *
     * @param policies the policies, in any order; they are sorted by priority, keeping the given order for ties
     * @param defaultDecision the decision when no policy applies; use a deny to fail closed
     * @throws InvalidPolicyException if two policies share an id
     */
    public PolicyEngine(List<Policy> policies, Decision defaultDecision) {
        this(policies, List.of(), defaultDecision);
    }

    /**
     * Creates an engine.
     *
     * @param policies the ordered policies; they are sorted by priority, keeping the given order for ties
     * @param mandatoryPolicies policies that apply to every call they match and can only make the outcome stricter;
     *     their decision must be deny or require-approval
     * @param defaultDecision the decision when no policy applies; use a deny to fail closed
     * @throws InvalidPolicyException if two policies share an id, or a mandatory policy allows
     */
    public PolicyEngine(List<Policy> policies, List<Policy> mandatoryPolicies, Decision defaultDecision) {
        Objects.requireNonNull(policies, "policies");
        Objects.requireNonNull(mandatoryPolicies, "mandatoryPolicies");
        this.defaultDecision = Objects.requireNonNull(defaultDecision, "defaultDecision");
        Set<String> ids = new HashSet<>();
        Stream.concat(policies.stream(), mandatoryPolicies.stream()).forEach(policy -> {
            if (!ids.add(policy.id())) {
                throw new InvalidPolicyException("Duplicate policy id: '" + policy.id() + "'");
            }
        });
        for (Policy mandatory : mandatoryPolicies) {
            if (mandatory.decision() instanceof Decision.Allow) {
                throw new InvalidPolicyException(
                        "Mandatory policy '" + mandatory.id() + "' must deny or require approval, not allow");
            }
        }
        // Stream.sorted is stable on an ordered stream, so policies with the same priority keep the order given.
        this.policies = policies.stream()
                .sorted(Comparator.comparingInt(Policy::priority))
                .toList();
        this.mandatoryPolicies = List.copyOf(mandatoryPolicies);
    }

    /**
     * Decides what to do with an invocation.
     *
     * @param invocation the invocation to evaluate
     * @return the decision and why it was reached; never {@code null}, never throws for a failing condition
     */
    public EvaluationResult evaluate(ToolInvocation invocation) {
        Objects.requireNonNull(invocation, "invocation");
        EvaluationResult ordered = null;
        for (Policy policy : policies) {
            Boolean applies = applies(policy, invocation);
            if (applies == null) {
                return EvaluationResult.failed(policy.id());
            }
            if (applies) {
                ordered = EvaluationResult.matched(policy.id(), policy.decision());
                break;
            }
        }

        Policy strictestMandatory = null;
        for (Policy mandatory : mandatoryPolicies) {
            Boolean applies = applies(mandatory, invocation);
            if (applies == null) {
                return EvaluationResult.failed(mandatory.id());
            }
            if (applies
                    && (strictestMandatory == null
                            || strictness(mandatory.decision()) > strictness(strictestMandatory.decision()))) {
                strictestMandatory = mandatory;
            }
        }

        if (strictestMandatory == null) {
            return ordered != null ? ordered : EvaluationResult.defaultDecision(defaultDecision);
        }
        if (ordered == null || strictness(strictestMandatory.decision()) > strictness(ordered.decision())) {
            return EvaluationResult.matched(strictestMandatory.id(), strictestMandatory.decision());
        }
        return ordered;
    }

    /** Whether the policy applies: {@code null} if its condition threw. */
    private static @Nullable Boolean applies(Policy policy, ToolInvocation invocation) {
        if (!policy.targets(invocation.toolName())) {
            return false;
        }
        try {
            return policy.condition().matches(invocation);
        } catch (RuntimeException e) {
            // The exception type is safe to log; the message might quote argument values, so it goes to DEBUG.
            LOG.log(
                    System.Logger.Level.WARNING,
                    "Policy ''{0}'' failed on tool ''{1}'' ({2}); denying the call",
                    policy.id(),
                    invocation.toolName(),
                    e.getClass().getName());
            LOG.log(System.Logger.Level.DEBUG, "Policy '" + policy.id() + "' failure details", e);
            return null;
        }
    }

    private static int strictness(Decision decision) {
        return switch (decision) {
            case Decision.Allow allow -> 0;
            case Decision.RequireApproval approval -> 1;
            case Decision.Deny deny -> 2;
        };
    }

    /**
     * Returns the ordered policies, in evaluation order.
     *
     * @return an unmodifiable list, sorted by priority
     */
    public List<Policy> policies() {
        return policies;
    }

    /**
     * Returns the mandatory policies.
     *
     * @return an unmodifiable list
     */
    public List<Policy> mandatoryPolicies() {
        return mandatoryPolicies;
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
