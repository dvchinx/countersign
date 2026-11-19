# 8. Human approval: the guard owns the timeout, the receipt names the approver

- Status: Accepted
- Date: 2026-11-19

## Context

`require-approval` was refused unconditionally until now (ADR 0005). Turning it into a real human-in-the-loop step
raises four questions:

- **Who enforces the deadline?** A gateway that never answers (a crashed Slack bot, a lost message) must not leave a
  call hanging forever, or let it through.
- **When is the receipt written?** Before asking, after the answer, or both?
- **What does the approver see?** Receipts never hold raw arguments, but nobody can approve a transfer without
  seeing the amount.
- **What happens with no approver configured?**

## Decision

- **Port.** `core.spi.ApprovalGateway` has one method,
  `CompletionStage<ApprovalDecision> request(ApprovalRequest)`. The gateway only delivers the question and the
  answer. `ApprovalDecision` is sealed: `Approved(approver)` or `Rejected(approver, reason)`.
- **The guard owns the timeout.** `ToolCallGuard` waits on the stage for at most the configured timeout
  (`countersign.approval.timeout`, default 2 minutes). On timeout it cancels the stage, so the gateway can withdraw
  the question, and refuses the call. A gateway that throws, fails, answers `null`, or is interrupted also refuses
  the call. Only `Approved` lets the tool run.
- **One receipt per call, written after the answer and before the tool runs.** The receipt keeps the decision
  (`REQUIRE_APPROVAL`) and gains two signed fields:
  - `approval`: `APPROVED`, `REJECTED`, `TIMED_OUT` or `UNAVAILABLE`;
  - `approver`: who answered, present only for `APPROVED` and `REJECTED`.

  The audit trail therefore answers "who allowed it?" directly. A rejection's free-text reason is shown to the model
  but not stored, since it may repeat argument values.
- **The approver sees the arguments.** `ApprovalRequest` carries the full `ToolInvocation`. Gateways may display it
  but must not log or store it unless the application chose to.
- **No gateway means immediate denial (`UNAVAILABLE`),** not a wait for the timeout, so a chat request never
  hangs for minutes for nothing. The starter warns at startup when policies require approval and no gateway is
  defined.
- `core.approval.InMemoryApprovalGateway` provides the building block for an approval screen inside the same
  application: `pending()`, `approve(id, approver)` and `reject(id, approver, reason)`.

## Consequences

- The deny-on-timeout rule holds for every gateway, including ones written by contributors, without them having to
  implement it.
- The calling thread blocks while it waits. That is fine on virtual threads, but HTTP timeouts in front of the
  agent must be longer than the approval timeout, or users see an error while the approval is still pending. This
  is documented next to the property.
- If the application crashes while a call waits, no receipt exists for it. Nothing ran, so nothing is missing from
  the audit of executed actions; recording pending requests would need a second receipt type and is left for
  later.
- Approvals held by `InMemoryApprovalGateway` are lost on restart and can only be answered on the instance that
  holds them. Multi-instance deployments need a shared gateway (a good first contribution).
