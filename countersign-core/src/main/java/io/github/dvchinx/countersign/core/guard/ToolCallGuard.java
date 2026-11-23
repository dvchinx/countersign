package io.github.dvchinx.countersign.core.guard;

import io.github.dvchinx.countersign.core.model.ApprovalDecision;
import io.github.dvchinx.countersign.core.model.ApprovalOutcome;
import io.github.dvchinx.countersign.core.model.ApprovalRequest;
import io.github.dvchinx.countersign.core.model.Decision;
import io.github.dvchinx.countersign.core.model.DecisionEvent;
import io.github.dvchinx.countersign.core.model.EvaluationResult;
import io.github.dvchinx.countersign.core.model.JsonObject;
import io.github.dvchinx.countersign.core.model.Receipt;
import io.github.dvchinx.countersign.core.model.ToolInvocation;
import io.github.dvchinx.countersign.core.policy.PolicyEngine;
import io.github.dvchinx.countersign.core.receipt.ReceiptChain;
import io.github.dvchinx.countersign.core.spi.ApprovalGateway;
import io.github.dvchinx.countersign.core.spi.AuditSink;
import io.github.dvchinx.countersign.core.spi.DecisionListener;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.jspecify.annotations.Nullable;

/**
 * Runs the decision path for one tool call: evaluate, ask for approval if needed, record a receipt, publish it, and
 * return a {@link Verdict}.
 *
 * <p>The receipt is recorded after any approval and <em>before</em> the tool runs, so there is never a tool execution
 * without a receipt, and the receipt says who approved it. Every failure on the way ends in {@link Verdict.Refuse}
 * (fail closed):
 *
 * <ul>
 *   <li>a policy that throws: the engine denies, and the denial is recorded;
 *   <li>arguments that cannot be read: see {@link #refuseInvalidArguments};
 *   <li>an approval that is rejected, times out, or cannot be asked (no gateway, gateway error): refused and recorded
 *       with its {@link ApprovalOutcome};
 *   <li>a receipt that cannot be hashed or signed: refused without a receipt;
 *   <li>an audit sink that throws: logged as an error, and refused when strict auditing is on.
 * </ul>
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

    private final PolicyEngine engine;
    private final ReceiptChain receipts;
    private final AuditSink auditSink;
    private final @Nullable ApprovalGateway approvals;
    private final Duration approvalTimeout;
    private final boolean strictAudit;
    private final Clock clock;
    private final List<DecisionListener> listeners;

    private ToolCallGuard(Builder builder) {
        this.engine = builder.engine;
        this.receipts = builder.receipts;
        this.auditSink = builder.auditSink;
        this.approvals = builder.approvals;
        this.approvalTimeout = builder.approvalTimeout;
        this.strictAudit = builder.strictAudit;
        this.clock = builder.clock;
        this.listeners = List.copyOf(builder.listeners);
    }

    /**
     * Starts building a guard. With nothing else set, there is no approval gateway (calls that need approval are
     * refused), the approval timeout is {@link #DEFAULT_APPROVAL_TIMEOUT}, and auditing is not strict.
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
        long start = System.nanoTime();
        EvaluationResult result = engine.evaluate(invocation);
        Duration evaluationTime = Duration.ofNanos(System.nanoTime() - start);
        if (result.decision() instanceof Decision.RequireApproval approval) {
            return decide(
                    invocation,
                    result,
                    askForApproval(invocation, result.policyId(), approval.reason()),
                    evaluationTime);
        }
        return decide(invocation, result, null, evaluationTime);
    }

    /**
     * Records and refuses a call whose input could not be read as a JSON object.
     *
     * <p>Such a call must never reach the tool: the policies could not look at it, and the tool's own parser might
     * read it differently than ours would.
     *
     * @param toolName the tool the model tried to call
     * @param invocationId the id to record
     * @param principal who the agent acts for, or {@code null}
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
        ToolInvocation invocation =
                new ToolInvocation(invocationId, toolName, JsonObject.empty(), principal, conversationId, timestamp);
        return decide(invocation, EvaluationResult.invalidArguments(), null, Duration.ZERO);
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
            ToolInvocation invocation, EvaluationResult result, @Nullable Approval approval, Duration evaluationTime) {
        Receipt receipt;
        try {
            receipt = approval == null
                    ? receipts.append(invocation, result)
                    : receipts.append(invocation, result, approval.outcome(), approval.approver());
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
                        new Verdict.Refuse(result.decision().type(), AUDIT_FAILED, receipt));
            }
        }

        Verdict verdict = switch (result.decision()) {
            case Decision.Allow allow -> new Verdict.Proceed(receipt);
            case Decision.Deny deny -> new Verdict.Refuse(Decision.Type.DENY, deny.reason(), receipt);
            case Decision.RequireApproval required ->
                Objects.requireNonNull(approval).outcome() == ApprovalOutcome.APPROVED
                        ? new Verdict.Proceed(receipt)
                        : new Verdict.Refuse(Decision.Type.REQUIRE_APPROVAL, approval.message(), receipt);
        };
        return publish(invocation, result, approval, evaluationTime, auditFailed, verdict);
    }

    /** Tells every listener what was decided, then returns the verdict unchanged. */
    private Verdict publish(
            ToolInvocation invocation,
            EvaluationResult result,
            @Nullable Approval approval,
            Duration evaluationTime,
            boolean auditFailed,
            Verdict verdict) {
        if (listeners.isEmpty()) {
            return verdict;
        }
        DecisionEvent event = new DecisionEvent(
                invocation.toolName(),
                result.decision().type(),
                result.basis(),
                result.policyId(),
                approval == null ? null : approval.outcome(),
                verdict instanceof Verdict.Proceed,
                evaluationTime,
                switch (verdict) {
                    case Verdict.Proceed proceed -> proceed.receipt();
                    case Verdict.Refuse refuse -> refuse.receipt();
                },
                auditFailed);
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
         * Builds the guard.
         *
         * @return the guard
         */
        public ToolCallGuard build() {
            return new ToolCallGuard(this);
        }
    }
}
