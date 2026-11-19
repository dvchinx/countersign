# 5. The decision path lives in the core, and receipts come before execution

- Status: Accepted
- Date: 2026-11-19

## Context

Every adapter (Spring AI `ToolCallback`s today, MCP servers next) must run the same steps for a tool call:
evaluate the policies, record a signed receipt, publish it, then run or refuse the tool. If each adapter wired
these steps itself, the fail-closed rules would be reimplemented, and eventually diverge, in every adapter.

Two more questions came up while building `GuardedToolCallback`:

- **When is the receipt written?** The first diagram in `CLAUDE.md` wrote it after the tool ran. The README
  promises a receipt "before anything runs".
- **Which arguments do policies see?** The policy engine judges arguments parsed by Countersign, but the tool
  parses the raw input again with its own parser. If the two parsers disagree (duplicate keys, trailing data), a
  model could show the policy one value and the tool another.

## Decision

- `core.guard.ToolCallGuard` runs the whole decision path and returns a sealed `Verdict`: `Proceed(receipt)` or
  `Refuse(decision, reason, receipt)`. Adapters only translate their types to a `ToolInvocation` and a `Verdict`
  back to their own world.
- **The receipt is signed and handed to the `AuditSink` before the tool runs.** There is never a tool execution
  without a receipt. If the receipt cannot be created, the call is refused. If the sink fails, the error is
  logged and, with strict auditing, the call is refused.
- A new port, `core.spi.AuditSink` (one method, `append(Receipt)`), receives the receipts.
- **Input is parsed strictly** and refused if it is not exactly one JSON object without duplicate member names.
  The refusal is recorded with the new basis `INVALID_ARGUMENTS`.
- The tool receives the **original** input unchanged. Re-serializing the parsed arguments would change number
  formats that some tools rely on; strict parsing removes the ambiguity instead.
- Until the `ApprovalGateway` exists, `REQUIRE_APPROVAL` is refused. That is the same outcome as the default
  deny-on-timeout gateway.

## Consequences

- MCP and any later adapter reuse the same, tested fail-closed logic.
- A receipt records the *decision*, not the tool's outcome. If the tool fails after being allowed, the receipt
  still says `ALLOW`. Recording outcomes (and approval results) will need additional fields or a follow-up
  receipt, decided with the approval gateway.
- A model that sends sloppy JSON gets a refusal instead of a lenient guess. This is deliberate.
