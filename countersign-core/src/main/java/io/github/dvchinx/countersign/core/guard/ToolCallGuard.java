package io.github.dvchinx.countersign.core.guard;

import io.github.dvchinx.countersign.core.model.Decision;
import io.github.dvchinx.countersign.core.model.EvaluationResult;
import io.github.dvchinx.countersign.core.model.JsonObject;
import io.github.dvchinx.countersign.core.model.Receipt;
import io.github.dvchinx.countersign.core.model.ToolInvocation;
import io.github.dvchinx.countersign.core.policy.PolicyEngine;
import io.github.dvchinx.countersign.core.receipt.ReceiptChain;
import io.github.dvchinx.countersign.core.spi.AuditSink;
import java.time.Instant;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Runs the decision path for one tool call: evaluate, record a receipt, publish it, and return a {@link Verdict}.
 *
 * <p>The receipt is recorded <em>before</em> the tool runs, so there is never a tool execution without a receipt.
 * Every failure on the way ends in {@link Verdict.Refuse} (fail closed):
 *
 * <ul>
 *   <li>a policy that throws: the engine denies, and the denial is recorded;
 *   <li>arguments that cannot be read: see {@link #refuseInvalidArguments};
 *   <li>a receipt that cannot be hashed or signed: refused without a receipt;
 *   <li>an audit sink that throws: logged as an error, and refused when strict auditing is on.
 * </ul>
 *
 * <p>{@code REQUIRE_APPROVAL} is refused for now: no approver is wired in yet, and refusing is what the default
 * deny-on-timeout approval gateway will do.
 *
 * <p>Thread-safe; never throws for a failure on the decision path.
 */
public final class ToolCallGuard {

    private static final System.Logger LOG = System.getLogger(ToolCallGuard.class.getName());

    private static final String RECEIPT_FAILED = "The call was blocked because Countersign could not record it";
    private static final String AUDIT_FAILED = "The call was blocked because Countersign could not store its record";

    private final PolicyEngine engine;
    private final ReceiptChain receipts;
    private final AuditSink auditSink;
    private final boolean strictAudit;

    /**
     * Creates a guard.
     *
     * @param engine decides what to do with each call
     * @param receipts records a signed, chained receipt for each call
     * @param auditSink stores each receipt
     * @param strictAudit {@code true} to refuse a call when its receipt cannot be stored; {@code false} to log the
     *     failure and let the decision stand
     */
    public ToolCallGuard(PolicyEngine engine, ReceiptChain receipts, AuditSink auditSink, boolean strictAudit) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.receipts = Objects.requireNonNull(receipts, "receipts");
        this.auditSink = Objects.requireNonNull(auditSink, "auditSink");
        this.strictAudit = strictAudit;
    }

    /**
     * Checks a tool call and records its receipt.
     *
     * @param invocation the call the model proposed
     * @return {@link Verdict.Proceed} if the tool may run, otherwise {@link Verdict.Refuse}
     */
    public Verdict check(ToolInvocation invocation) {
        Objects.requireNonNull(invocation, "invocation");
        return decide(invocation, engine.evaluate(invocation));
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
        return decide(invocation, EvaluationResult.invalidArguments());
    }

    private Verdict decide(ToolInvocation invocation, EvaluationResult result) {
        Receipt receipt;
        try {
            receipt = receipts.append(invocation, result);
        } catch (RuntimeException e) {
            LOG.log(
                    System.Logger.Level.ERROR,
                    "Could not record a receipt for tool ''{0}'' ({1}); refusing the call",
                    invocation.toolName(),
                    e.getClass().getName());
            LOG.log(System.Logger.Level.DEBUG, "Receipt failure details", e);
            return new Verdict.Refuse(result.decision().type(), RECEIPT_FAILED, null);
        }

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
            if (strictAudit) {
                return new Verdict.Refuse(result.decision().type(), AUDIT_FAILED, receipt);
            }
        }

        return switch (result.decision()) {
            case Decision.Allow allow -> new Verdict.Proceed(receipt);
            case Decision.Deny deny -> new Verdict.Refuse(Decision.Type.DENY, deny.reason(), receipt);
            case Decision.RequireApproval approval ->
                new Verdict.Refuse(
                        Decision.Type.REQUIRE_APPROVAL,
                        "This call needs human approval (" + approval.reason() + ") and no approver is available",
                        receipt);
        };
    }
}
