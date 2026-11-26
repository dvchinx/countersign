package io.github.dvchinx.countersign.core.guard;

import io.github.dvchinx.countersign.core.model.ApprovalDecision;
import io.github.dvchinx.countersign.core.model.ApprovalOutcome;
import io.github.dvchinx.countersign.core.model.ApprovalRequest;
import io.github.dvchinx.countersign.core.model.Decision;
import io.github.dvchinx.countersign.core.model.DecisionEvent;
import io.github.dvchinx.countersign.core.model.EvaluationResult;
import io.github.dvchinx.countersign.core.model.JsonObject;
import io.github.dvchinx.countersign.core.model.Receipt;
import io.github.dvchinx.countersign.core.model.Redaction;
import io.github.dvchinx.countersign.core.model.ToolInvocation;
import io.github.dvchinx.countersign.core.policy.InvalidPolicyException;
import io.github.dvchinx.countersign.core.policy.Policy;
import io.github.dvchinx.countersign.core.policy.PolicyEngine;
import io.github.dvchinx.countersign.core.receipt.ReceiptChain;
import io.github.dvchinx.countersign.core.redaction.RedactionRule;
import io.github.dvchinx.countersign.core.spi.ApprovalGateway;
import io.github.dvchinx.countersign.core.spi.AuditSink;
import io.github.dvchinx.countersign.core.spi.DecisionListener;
import io.github.dvchinx.countersign.core.spi.PrincipalResolver;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * Runs the decision path for one tool call: evaluate, find the redaction rules that apply, ask for approval if needed,
 * record a receipt, publish it, and return a {@link Verdict}.
 *
 * <p>The receipt is recorded after any approval and <em>before</em> the tool runs, so there is never a tool execution
 * without a receipt, and the receipt says who approved it. Every failure on the way ends in {@link Verdict.Refuse}
 * (fail closed):
 *
 * <ul>
 *   <li>a {@link PrincipalResolver} that throws: refused without a receipt, before any policy is evaluated;
 *   <li>a policy that throws: the engine denies, and the denial is recorded;
 *   <li>a redaction rule whose condition throws: refused and recorded as
 *       {@link EvaluationResult.Basis#EVALUATION_FAILED} with the rule's id, before anyone is asked to approve. A rule
 *       that cannot be checked might have been needed;
 *   <li>arguments that cannot be read: see {@link #refuseInvalidArguments};
 *   <li>an approval that is rejected, times out, or cannot be asked (no gateway, gateway error): refused and recorded
 *       with its {@link ApprovalOutcome};
 *   <li>a receipt that cannot be hashed or signed: refused without a receipt;
 *   <li>an audit sink that throws: logged as an error, and refused when strict auditing is on.
 * </ul>
 *
 * <p>Redaction is an obligation, not a decision (ADR 0015): every {@link RedactionRule} that matches a call that runs
 * applies to it, whatever decided access. Rules are matched on the arguments the model proposed, the same ones the
 * policies and the approver see, and only when the policies did not deny the call. The receipt lists the rules that
 * apply; {@link Verdict.Proceed#resultRedaction()} carries what the adapter must redact from the result. If that
 * fails, the result is withheld, the failure is logged, and every listener's
 * {@link DecisionListener#onResultWithheld} is called.
 *
 * <p>When the invocation carries no principal and a {@link PrincipalResolver} is set, the guard asks it who the call
 * acts for, and evaluates and records the invocation with that principal. A principal the adapter passed explicitly
 * is never replaced.
 *
 * <p>Once a call is decided, every {@link DecisionListener} hears about it, before the tool runs. A listener that
 * throws is logged and ignored; it never changes the verdict.
 *
 * <p>Waiting for approval blocks the calling thread until the answer or the timeout, whichever comes first. Use
 * virtual threads, or keep the timeout short, if many calls may wait at once. Thread-safe; never throws for a failure
 * on the decision path.
 */
public final class ToolCallGuard {

    /** The approval timeout used when none is set. */
    public static final Duration DEFAULT_APPROVAL_TIMEOUT = Duration.ofMinutes(2);

    private static final System.Logger LOG = System.getLogger(ToolCallGuard.class.getName());

    private static final String RECEIPT_FAILED = "The call was blocked because Countersign could not record it";
    private static final String AUDIT_FAILED = "The call was blocked because Countersign could not store its record";
    private static final String PRINCIPAL_FAILED =
            "The call was blocked because Countersign could not tell who it acts for";

    private final PolicyEngine engine;
    private final ReceiptChain receipts;
    private final AuditSink auditSink;
    private final @Nullable ApprovalGateway approvals;
    private final Duration approvalTimeout;
    private final boolean strictAudit;
    private final Clock clock;
    private final List<DecisionListener> listeners;
    private final @Nullable PrincipalResolver principalResolver;
    private final List<RedactionRule> redactionRules;
    private final AtomicBoolean missingPrincipalReported = new AtomicBoolean();

    private ToolCallGuard(Builder builder) {
        this.engine = builder.engine;
        this.receipts = builder.receipts;
        this.auditSink = builder.auditSink;
        this.approvals = builder.approvals;
        this.approvalTimeout = builder.approvalTimeout;
        this.strictAudit = builder.strictAudit;
        this.clock = builder.clock;
        this.listeners = List.copyOf(builder.listeners);
        this.principalResolver = builder.principalResolver;
        this.redactionRules = List.copyOf(builder.redactionRules);
        checkIdsAreUnique(engine, redactionRules);
    }

    /** Rule ids share one namespace with policy ids, so the ids in a receipt are never ambiguous (ADR 0015). */
    private static void checkIdsAreUnique(PolicyEngine engine, List<RedactionRule> rules) {
        Set<String> policyIds = new HashSet<>();
        Stream.concat(engine.policies().stream(), engine.mandatoryPolicies().stream())
                .map(Policy::id)
                .forEach(policyIds::add);
        Set<String> ruleIds = new HashSet<>();
        for (RedactionRule rule : rules) {
            if (!ruleIds.add(rule.id())) {
                throw new InvalidPolicyException("Duplicate redaction rule id: '" + rule.id() + "'");
            }
            if (policyIds.contains(rule.id())) {
                throw new InvalidPolicyException(
                        "Redaction rule id '" + rule.id() + "' is already a policy id; ids must be unique across both");
            }
        }
    }

    /**
     * Starts building a guard. With nothing else set, there is no approval gateway (calls that need approval are
     * refused), the approval timeout is {@link #DEFAULT_APPROVAL_TIMEOUT}, auditing is not strict, and nothing is
     * redacted.
     *
     * @param engine decides what to do with each call
     * @param receipts records a signed, chained receipt for each call
     * @param auditSink stores each receipt
     * @return a builder
     */
    public static Builder builder(PolicyEngine engine, ReceiptChain receipts, AuditSink auditSink) {
        return new Builder(engine, receipts, auditSink);
    }

    /**
     * Checks a tool call, asking for approval if a policy requires it, and records its receipt.
     *
     * @param invocation the call the model proposed
     * @return {@link Verdict.Proceed} if the tool may run, otherwise {@link Verdict.Refuse}
     */
    public Verdict check(ToolInvocation invocation) {
        Objects.requireNonNull(invocation, "invocation");
        try {
            invocation = withPrincipal(invocation);
        } catch (RuntimeException e) {
            LOG.log(
                    System.Logger.Level.ERROR,
                    "Could not tell who tool ''{0}'' acts for ({1}); refusing the call",
                    invocation.toolName(),
                    e.getClass().getName());
            LOG.log(System.Logger.Level.DEBUG, "Principal resolver failure details", e);
            return new Verdict.Refuse(Decision.Type.DENY, PRINCIPAL_FAILED, null);
        }
        long start = System.nanoTime();
        EvaluationResult result = engine.evaluate(invocation);
        List<RedactionRule> redactions = List.of();
        if (!(result.decision() instanceof Decision.Deny)) {
            // Before approval: nobody should be asked to approve a call that is refused anyway.
            Matched matched = matchRedactions(invocation);
            if (matched.failedRuleId() != null) {
                result = EvaluationResult.failed(matched.failedRuleId());
            } else {
                redactions = matched.rules();
            }
        }
        Duration evaluationTime = Duration.ofNanos(System.nanoTime() - start);
        if (result.decision() instanceof Decision.RequireApproval approval) {
            return decide(
                    invocation,
                    result,
                    askForApproval(invocation, result.policyId(), approval.reason()),
                    redactions,
                    evaluationTime);
        }
        return decide(invocation, result, null, redactions, evaluationTime);
    }

    /** The redaction rules that apply to a call, or the first one whose condition failed. */
    private record Matched(
            List<RedactionRule> rules, @Nullable String failedRuleId) {}

    private Matched matchRedactions(ToolInvocation invocation) {
        List<RedactionRule> matched = new ArrayList<>();
        for (RedactionRule rule : redactionRules) {
            try {
                if (rule.appliesTo(invocation)) {
                    matched.add(rule);
                }
            } catch (RuntimeException e) {
                // The exception type is safe to log; the message might quote argument values, so it goes to DEBUG.
                LOG.log(
                        System.Logger.Level.WARNING,
                        "Redaction rule ''{0}'' failed on tool ''{1}'' ({2}); refusing the call",
                        rule.id(),
                        invocation.toolName(),
                        e.getClass().getName());
                LOG.log(System.Logger.Level.DEBUG, "Redaction rule '" + rule.id() + "' failure details", e);
                return new Matched(List.of(), rule.id());
            }
        }
        return new Matched(matched, null);
    }

    /**
     * Returns the redaction rules, in the order they are applied.
     *
     * @return an unmodifiable list
     */
    public List<RedactionRule> redactionRules() {
        return redactionRules;
    }

    /**
     * Records and refuses a call whose input could not be read as a JSON object.
     *
     * <p>Such a call must never reach the tool: the policies could not look at it, and the tool's own parser might
     * read it differently than ours would.
     *
     * @param toolName the tool the model tried to call
     * @param invocationId the id to record
     * @param principal who the agent acts for, or {@code null} to ask the {@link PrincipalResolver}, if any
     * @param conversationId the conversation, or {@code null}
     * @param timestamp when the call was received
     * @return always a {@link Verdict.Refuse}
     */
    public Verdict refuseInvalidArguments(
            String toolName,
            String invocationId,
            @Nullable String principal,
            @Nullable String conversationId,
            Instant timestamp) {
        if (principal == null && principalResolver != null) {
            try {
                principal = resolvePrincipal(toolName);
            } catch (RuntimeException e) {
                // The call is refused anyway; record it without a principal rather than not at all.
                LOG.log(
                        System.Logger.Level.ERROR,
                        "Could not tell who tool ''{0}'' acts for ({1})",
                        toolName,
                        e.getClass().getName());
                LOG.log(System.Logger.Level.DEBUG, "Principal resolver failure details", e);
            }
        }
        ToolInvocation invocation =
                new ToolInvocation(invocationId, toolName, JsonObject.empty(), principal, conversationId, timestamp);
        return decide(invocation, EvaluationResult.invalidArguments(), null, List.of(), Duration.ZERO);
    }

    /** Returns the invocation with the resolver's principal when it has none of its own. */
    private ToolInvocation withPrincipal(ToolInvocation invocation) {
        if (invocation.principal() != null || principalResolver == null) {
            return invocation;
        }
        String principal = resolvePrincipal(invocation.toolName());
        return principal == null
                ? invocation
                : new ToolInvocation(
                        invocation.id(),
                        invocation.toolName(),
                        invocation.arguments(),
                        principal,
                        invocation.conversationId(),
                        invocation.timestamp());
    }

    /** Asks the resolver, treating a blank name as unknown; warns once if a call has no principal. */
    private @Nullable String resolvePrincipal(String toolName) {
        PrincipalResolver resolver = Objects.requireNonNull(principalResolver);
        String principal = resolver.currentPrincipal();
        if (principal != null && !principal.isBlank()) {
            return principal;
        }
        if (missingPrincipalReported.compareAndSet(false, true)) {
            LOG.log(
                    System.Logger.Level.WARNING,
                    "Tool ''{0}'' was called with no principal: {1} returned none, so the receipt does not say who"
                            + " asked for it. If the user is signed in, the tool may run on a thread that does not"
                            + " carry their security context. This warning is shown once.",
                    toolName,
                    resolver.getClass().getName());
        }
        return null;
    }

    /** The answer to an approval request, as Countersign records and reports it. */
    private record Approval(
            ApprovalOutcome outcome, @Nullable String approver, String message) {}

    private Approval askForApproval(ToolInvocation invocation, @Nullable String policyId, String reason) {
        if (approvals == null) {
            return new Approval(
                    ApprovalOutcome.UNAVAILABLE,
                    null,
                    "This call needs human approval (" + reason + ") and no approver is available");
        }
        ApprovalRequest request = new ApprovalRequest(
                invocation, policyId, reason, clock.instant().plus(approvalTimeout));
        CompletableFuture<ApprovalDecision> answer;
        try {
            answer = approvals.request(request).toCompletableFuture();
        } catch (RuntimeException e) {
            return unavailable(invocation, reason, e);
        }
        try {
            ApprovalDecision decision = answer.get(approvalTimeout.toNanos(), TimeUnit.NANOSECONDS);
            return switch (decision) {
                case null -> unavailable(invocation, reason, new IllegalStateException("null approval decision"));
                case ApprovalDecision.Approved approved ->
                    new Approval(ApprovalOutcome.APPROVED, approved.approver(), "");
                case ApprovalDecision.Rejected rejected ->
                    new Approval(
                            ApprovalOutcome.REJECTED,
                            rejected.approver(),
                            "The call was rejected by " + rejected.approver()
                                    + (rejected.reason() == null
                                                    || rejected.reason().isBlank()
                                            ? ""
                                            : ": " + rejected.reason()));
            };
        } catch (TimeoutException e) {
            answer.cancel(false);
            return new Approval(
                    ApprovalOutcome.TIMED_OUT,
                    null,
                    "This call needed human approval (" + reason + ") and no answer arrived in time");
        } catch (InterruptedException e) {
            answer.cancel(false);
            Thread.currentThread().interrupt();
            return unavailable(invocation, reason, e);
        } catch (ExecutionException | CancellationException e) {
            return unavailable(invocation, reason, e);
        }
    }

    private Approval unavailable(ToolInvocation invocation, String reason, Exception e) {
        LOG.log(
                System.Logger.Level.ERROR,
                "Could not get approval for tool ''{0}'' ({1}); refusing the call",
                invocation.toolName(),
                e.getClass().getName());
        LOG.log(System.Logger.Level.DEBUG, "Approval failure details", e);
        return new Approval(
                ApprovalOutcome.UNAVAILABLE,
                null,
                "This call needs human approval (" + reason + ") and the approver could not be reached");
    }

    private Verdict decide(
            ToolInvocation invocation,
            EvaluationResult result,
            @Nullable Approval approval,
            List<RedactionRule> redactions,
            Duration evaluationTime) {
        boolean runs = switch (result.decision()) {
            case Decision.Allow allow -> true;
            case Decision.Deny deny -> false;
            case Decision.RequireApproval required ->
                approval != null && approval.outcome() == ApprovalOutcome.APPROVED;
        };
        List<RedactionRule> applied = runs ? redactions : List.of();
        Receipt receipt;
        try {
            receipt = receipts.append(
                    invocation,
                    result,
                    approval == null ? null : approval.outcome(),
                    approval == null ? null : approval.approver(),
                    applied.stream().map(RedactionRule::id).toList());
        } catch (RuntimeException e) {
            LOG.log(
                    System.Logger.Level.ERROR,
                    "Could not record a receipt for tool ''{0}'' ({1}); refusing the call",
                    invocation.toolName(),
                    e.getClass().getName());
            LOG.log(System.Logger.Level.DEBUG, "Receipt failure details", e);
            return publish(
                    invocation,
                    result,
                    approval,
                    evaluationTime,
                    false,
                    List.of(),
                    new Verdict.Refuse(result.decision().type(), RECEIPT_FAILED, null));
        }

        boolean auditFailed = false;
        try {
            auditSink.append(receipt);
        } catch (RuntimeException e) {
            LOG.log(
                    System.Logger.Level.ERROR,
                    "Could not store receipt {0}#{1} for tool ''{2}'' ({3}){4}",
                    receipt.streamId(),
                    receipt.sequence(),
                    invocation.toolName(),
                    e.getClass().getName(),
                    strictAudit ? "; refusing the call (strict audit)" : "");
            LOG.log(System.Logger.Level.DEBUG, "Audit failure details", e);
            auditFailed = true;
            if (strictAudit) {
                return publish(
                        invocation,
                        result,
                        approval,
                        evaluationTime,
                        true,
                        List.of(),
                        new Verdict.Refuse(result.decision().type(), AUDIT_FAILED, receipt));
            }
        }

        Verdict verdict = switch (result.decision()) {
            case Decision.Allow allow -> new Verdict.Proceed(receipt, resultRedaction(invocation, receipt, applied));
            case Decision.Deny deny -> new Verdict.Refuse(Decision.Type.DENY, deny.reason(), receipt);
            case Decision.RequireApproval required ->
                runs
                        ? new Verdict.Proceed(receipt, resultRedaction(invocation, receipt, applied))
                        : new Verdict.Refuse(
                                Decision.Type.REQUIRE_APPROVAL,
                                Objects.requireNonNull(approval).message(),
                                receipt);
        };
        return publish(invocation, result, approval, evaluationTime, auditFailed, applied, verdict);
    }

    /** What the adapter must redact from the result: the rules among those that apply which target it. */
    private ResultRedaction resultRedaction(ToolInvocation invocation, Receipt receipt, List<RedactionRule> applied) {
        List<RedactionRule> onResult = applied.stream()
                .filter(rule -> rule.targets().contains(Redaction.Target.RESULT))
                .toList();
        return onResult.isEmpty()
                ? ResultRedaction.none()
                : new ResultRedaction(
                        onResult, (ruleId, failure) -> resultWithheld(invocation, receipt, ruleId, failure));
    }

    /** Logs a result that could not be redacted and tells every listener. Never throws. */
    private void resultWithheld(ToolInvocation invocation, Receipt receipt, String ruleId, RuntimeException failure) {
        LOG.log(
                System.Logger.Level.ERROR,
                "Redaction rule ''{0}'' could not redact the result of tool ''{1}'' ({2}); the result is withheld from"
                        + " the model, but the tool already ran (receipt {3}#{4})",
                ruleId,
                invocation.toolName(),
                failure.getClass().getName(),
                receipt.streamId(),
                receipt.sequence());
        LOG.log(System.Logger.Level.DEBUG, "Redaction failure details", failure);
        for (DecisionListener listener : listeners) {
            try {
                listener.onResultWithheld(receipt, ruleId);
            } catch (RuntimeException e) {
                LOG.log(
                        System.Logger.Level.ERROR,
                        "Decision listener {0} failed on a withheld result of tool ''{1}'' ({2})",
                        listener.getClass().getName(),
                        invocation.toolName(),
                        e.getClass().getName());
                LOG.log(System.Logger.Level.DEBUG, "Decision listener failure details", e);
            }
        }
    }

    /** Tells every listener what was decided, then returns the verdict unchanged. */
    private Verdict publish(
            ToolInvocation invocation,
            EvaluationResult result,
            @Nullable Approval approval,
            Duration evaluationTime,
            boolean auditFailed,
            List<RedactionRule> applied,
            Verdict verdict) {
        if (listeners.isEmpty()) {
            return verdict;
        }
        boolean proceeds = verdict instanceof Verdict.Proceed;
        DecisionEvent event = new DecisionEvent(
                invocation.toolName(),
                result.decision().type(),
                result.basis(),
                result.policyId(),
                approval == null ? null : approval.outcome(),
                proceeds,
                evaluationTime,
                switch (verdict) {
                    case Verdict.Proceed proceed -> proceed.receipt();
                    case Verdict.Refuse refuse -> refuse.receipt();
                },
                auditFailed,
                proceeds ? applied.stream().map(RedactionRule::redaction).toList() : List.of());
        for (DecisionListener listener : listeners) {
            try {
                listener.onDecision(event);
            } catch (RuntimeException e) {
                LOG.log(
                        System.Logger.Level.ERROR,
                        "Decision listener {0} failed for tool ''{1}'' ({2}); the decision stands",
                        listener.getClass().getName(),
                        invocation.toolName(),
                        e.getClass().getName());
                LOG.log(System.Logger.Level.DEBUG, "Decision listener failure details", e);
            }
        }
        return verdict;
    }

    /** Builds a {@link ToolCallGuard}. Not thread-safe. */
    public static final class Builder {

        private final PolicyEngine engine;
        private final ReceiptChain receipts;
        private final AuditSink auditSink;
        private @Nullable ApprovalGateway approvals;
        private Duration approvalTimeout = DEFAULT_APPROVAL_TIMEOUT;
        private boolean strictAudit;
        private Clock clock = Clock.systemUTC();
        private final List<DecisionListener> listeners = new ArrayList<>();
        private @Nullable PrincipalResolver principalResolver;
        private final List<RedactionRule> redactionRules = new ArrayList<>();

        private Builder(PolicyEngine engine, ReceiptChain receipts, AuditSink auditSink) {
            this.engine = Objects.requireNonNull(engine, "engine");
            this.receipts = Objects.requireNonNull(receipts, "receipts");
            this.auditSink = Objects.requireNonNull(auditSink, "auditSink");
        }

        /**
         * Sets who is asked when a policy requires approval. Without one, such calls are refused at once.
         *
         * @param approvals the approval gateway, or {@code null} for none
         * @return this builder
         */
        public Builder approvals(@Nullable ApprovalGateway approvals) {
            this.approvals = approvals;
            return this;
        }

        /**
         * Sets how long to wait for an approval before refusing the call.
         *
         * @param approvalTimeout a positive duration
         * @return this builder
         * @throws IllegalArgumentException if the duration is zero or negative
         */
        public Builder approvalTimeout(Duration approvalTimeout) {
            Objects.requireNonNull(approvalTimeout, "approvalTimeout");
            if (approvalTimeout.isNegative() || approvalTimeout.isZero()) {
                throw new IllegalArgumentException("approvalTimeout must be positive");
            }
            this.approvalTimeout = approvalTimeout;
            return this;
        }

        /**
         * Sets whether a call is refused when its receipt cannot be stored.
         *
         * @param strictAudit {@code true} to refuse; {@code false} to log the failure and let the decision stand
         * @return this builder
         */
        public Builder strictAudit(boolean strictAudit) {
            this.strictAudit = strictAudit;
            return this;
        }

        /**
         * Sets the clock used for approval deadlines.
         *
         * @param clock the clock
         * @return this builder
         */
        public Builder clock(Clock clock) {
            this.clock = Objects.requireNonNull(clock, "clock");
            return this;
        }

        /**
         * Adds a listener that hears about every decision, for metrics or tracing. Listeners are called in the order
         * they were added.
         *
         * @param listener the listener
         * @return this builder
         */
        public Builder listener(DecisionListener listener) {
            listeners.add(Objects.requireNonNull(listener, "listener"));
            return this;
        }

        /**
         * Sets who is asked for the principal of a call that carries none. Without one, such calls are evaluated
         * and recorded with no principal.
         *
         * @param principalResolver the resolver, or {@code null} for none
         * @return this builder
         */
        public Builder principalResolver(@Nullable PrincipalResolver principalResolver) {
            this.principalResolver = principalResolver;
            return this;
        }

        /**
         * Adds a redaction rule. Every rule that matches a call that runs applies to it, in the order added.
         *
         * @param rule the rule
         * @return this builder
         */
        public Builder redaction(RedactionRule rule) {
            redactionRules.add(Objects.requireNonNull(rule, "rule"));
            return this;
        }

        /**
         * Adds redaction rules, in order. See {@link #redaction(RedactionRule)}.
         *
         * @param rules the rules
         * @return this builder
         */
        public Builder redactions(Collection<RedactionRule> rules) {
            rules.forEach(this::redaction);
            return this;
        }

        /**
         * Builds the guard.
         *
         * @return the guard
         * @throws InvalidPolicyException if two redaction rules share an id, or a rule has the id of a policy
         */
        public ToolCallGuard build() {
            return new ToolCallGuard(this);
        }
    }
}
