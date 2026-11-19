package io.github.dvchinx.countersign.core.spi;

import io.github.dvchinx.countersign.core.model.ApprovalDecision;
import io.github.dvchinx.countersign.core.model.ApprovalRequest;
import java.util.concurrent.CompletionStage;

/**
 * Asks a human to approve or reject a tool call that a policy marked {@code require-approval}.
 *
 * <p>A port so approvals can happen wherever people already work: a web console, Slack, Teams, email. The gateway only
 * delivers the question and the answer; Countersign owns the rules around it:
 *
 * <ul>
 *   <li>It waits at most until {@link ApprovalRequest#deadline()}. If the stage is not complete by then, the call is
 *       refused as timed out and the stage is cancelled, so the gateway can withdraw the question.
 *   <li>A stage that completes exceptionally, a {@code null} answer, or an exception thrown by {@link #request}
 *       refuses the call.
 *   <li>The answer and the approver are recorded in the call's receipt.
 * </ul>
 *
 * <p>Return quickly: deliver the question and complete the stage later, from whatever thread receives the answer.
 * Implementations must be thread-safe. The request contains the raw arguments; see {@link ApprovalRequest}.
 */
@FunctionalInterface
public interface ApprovalGateway {

    /**
     * Asks for approval.
     *
     * @param request the call waiting for approval
     * @return a stage that completes with the human's answer
     */
    CompletionStage<ApprovalDecision> request(ApprovalRequest request);
}
