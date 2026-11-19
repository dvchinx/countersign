package io.github.dvchinx.countersign.core.model;

import java.time.Instant;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * A call waiting for a human to approve or reject it, as handed to an {@code ApprovalGateway}.
 *
 * <p>Unlike receipts, a request carries the raw {@linkplain ToolInvocation#arguments() arguments}: an approver cannot
 * judge "transfer to ACME" without seeing the amount. Gateways may show them to the approver but must not log or
 * store them unless the application has chosen to.
 *
 * @param invocation the call, with its arguments
 * @param policyId the policy that asked for approval, or {@code null} if it is the default decision
 * @param reason why approval is needed, to show the approver
 * @param deadline when Countersign stops waiting and refuses the call
 */
public record ApprovalRequest(
        ToolInvocation invocation, @Nullable String policyId, String reason, Instant deadline) {

    /** Creates a request. */
    public ApprovalRequest {
        Objects.requireNonNull(invocation, "invocation");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(deadline, "deadline");
    }
}
