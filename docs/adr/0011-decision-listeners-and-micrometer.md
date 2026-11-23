# 11. Decision listeners and Micrometer

- Status: Accepted
- Date: 2026-11-23

## Context

Operators need to see what Countersign does in production: how many calls each tool gets, how many are refused and
why, how long policy evaluation takes, and whether receipts are being lost. Design principle 6 requires a failed
audit write to surface as a metric, because without strict auditing the call goes on and the log line is the only
other trace. The README already promises `countersign.decisions`, `countersign.evaluation` and
`countersign.audit.failures`.

The facts behind those metrics live inside `ToolCallGuard` in `countersign-core`, which must not depend on Micrometer
(design principle 5). Evaluation time in particular cannot be measured from outside: `PolicyEngine` is final, and
timing `ToolCallGuard.check` would include minutes spent waiting for a human.

## Decision

### A port in the core

`core.spi.DecisionListener` has one method, `onDecision(DecisionEvent)`, so it can be a lambda.
`core.model.DecisionEvent` carries, for one call: the tool, the decision, its basis, the deciding policy, the approval
outcome, whether the tool runs, the time spent in `PolicyEngine.evaluate`, the receipt (or `null` if it could not be
created) and whether storing it failed. It never carries the arguments, so a listener cannot log them by accident
(design principle 3); the principal and conversation are in the receipt. The record enforces that a call never
proceeds without a receipt.

The guard calls every listener once per call, on the calling thread, after the audit write and before the tool runs.
A listener that throws is logged and skipped; the verdict never changes. Metrics observe enforcement, they are never
part of it.

### Micrometer adapters in `countersign-spring-ai`

`MicrometerDecisionListener` publishes:

| Meter | Type | Tags |
|---|---|---|
| `countersign.decisions` | counter | `tool`, `decision`, `policy`, `basis`, `outcome` (`proceed`/`refuse`), `approval` |
| `countersign.evaluation` | timer | `tool` |
| `countersign.audit.failures` | counter | `tool`, `stage` (`receipt`/`sink`) |

`basis`, `outcome` and `approval` are added to the tags planned in `CLAUDE.md`: without them a refused
require-approval call (rejected, timed out, no approver) looks the same as an approved one. Tag values are bounded:
tools are the registered ones (unknown tools never reach the guard), policies come from configuration, the rest are
enums. Values are lowercase with dashes, as in the YAML; `none` means no policy or no approval. Calls whose arguments
could not be read are counted but not timed.

`ObservationDecisionListener` adds `countersign.decision`, `countersign.outcome`, `countersign.policy` and
`countersign.receipt` (`streamId#sequence`) to the `spring.ai.tool` observation Spring AI opens around each tool
call. They are **high-cardinality** key values: they reach traces, but do not change the tags of Spring AI's own
`spring.ai.tool` timer, which would break registries such as Prometheus that need the same tag keys on every meter
of one name. The listener only touches the current observation when it is the call of the same tool.

We did not open a Countersign observation of our own: the guard runs inside Spring AI's tool observation, so a
separate span would only repeat its timing, and it would need an `ObservationRegistry` threaded through every
wrapper.

### Starter

`CountersignObservabilityAutoConfiguration` registers each listener when its registry bean exists (with Actuator,
both do). The guard receives every `DecisionListener` bean, so an application can add its own, for example to page
someone when `auditFailed` is true.

## Consequences

- Metrics and traces need no configuration beyond Actuator, and can be filtered with
  `management.metrics.enable.countersign=false`.
- Listeners add work to every tool call before it runs. The provided ones only touch in-memory meters.
- `DecisionListener` is a new public SPI. It has no contract test in `countersign-test`: it only receives events and
  has no observable behavior to check. The guard's own tests cover what it sends.
- Adding a tag later is a breaking change for dashboards; the tags above are the contract for `0.1.0`.
