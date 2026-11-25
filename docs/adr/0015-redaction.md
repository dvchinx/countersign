# 15. Redaction of tool arguments and results

- Status: Accepted
- Date: 2026-11-25

## Context

Countersign can let a tool call run or stop it. It cannot yet let a call run *with less data*. The common cases:

- **Results.** A `crm_find_customer` tool returns a record with an email, a phone number and a card number. The
  agent needs the record, but the card number should not reach the model, its provider or the conversation history.
  This is the case teams ask about most: tool results are the main way private data reaches a model.
- **Arguments.** The model passes a customer's email to a `web_search` tool that calls an outside service. The
  search can run, but the email should not leave.

CLAUDE.md and the README plan this as a fourth decision, `REDACT`, next to `ALLOW`, `DENY` and `REQUIRE_APPROVAL`,
applied by a `Redactor` port. 0.1.0 is published, so this is the first change to the decision model and the receipt
format since a release. Three things are fixed and constrain the design:

1. **Decisions are exclusive.** Ordered policies are first match wins. Mandatory policies (ADR 0009) can only make
   the outcome stricter, on the scale `ALLOW` < `REQUIRE_APPROVAL` < `DENY`.
2. **The receipt is written before the tool runs** (ADR 0003, ADR 0008), so it can record what will be done to a
   result but not the result itself.
3. **Receipts from 0.1.0 must keep verifying.** ADR 0003 left the format without a version field and said any change
   after 0.1.0 needs an ADR and a way for old receipts to keep their hashes.

## Options

### A. `REDACT` as a fourth decision

`Decision.Redact(rules)` joins the sealed interface, `Decision.Type` gains `REDACT`, and a policy with
`decision: redact` is first match wins like any other.

Redaction would then compete with access decisions instead of combining with them:

- A transfer that needs approval *and* a masked result needs one policy that does both, because only one decision
  can match.
- A matching `@RequiresApproval` is stricter than `REDACT`, so it wins, and **the redaction is silently dropped**: the
  approved call returns the full result. Ranking `REDACT` above `REQUIRE_APPROVAL` instead would let a redaction
  policy remove a mandatory approval. Neither is acceptable for a security feature.
- An `allow` policy listed before the redaction policy also drops it, through ordinary policy ordering, which is
  easy to get wrong when the policy list is long.
- `Decision` and `Decision.Type` are public, sealed and exhaustive by design. A new variant breaks every
  `switch` over them in user code (a "Breaking" CHANGELOG entry), and `REDACT` appears in metrics and receipts.

### B. Redaction as an obligation on calls that run (recommended)

Access stays a three-valued decision. Redaction is a separate kind of rule: **every** redaction rule that matches a
call applies to it, whatever decided access, as long as the call runs. This is the "obligation" in XACML: the
decision says whether, the obligations say how.

- Approval and redaction combine with no special case: an approved transfer still gets its result masked.
- No policy order can drop a redaction, and no redaction can weaken an access decision.
- `Decision` and `Decision.Type` do not change, so there is no breaking change to the decision model.

The cost is that the README and CLAUDE.md, which describe `REDACT` as a decision, have to change, and the receipt
needs a new field to show that a call was redacted, because its `decision` is still `ALLOW` (or
`REQUIRE_APPROVAL` + `APPROVED`).

## Decision

Option B.

### Rules

A `RedactionRule` (`core.redaction`) has an id, tool globs, an optional condition (the same sandboxed SpEL as
policies, over `#args`, `#tool`, `#principal`, `#conversationId`), a target (`ARGS`, `RESULT` or both) and the
names of the redactors to apply, in order. Rule ids share one namespace with policy ids, so a receipt's ids are
never ambiguous.

```yaml
countersign:
  redactors:
    card-number:
      pattern: "\\b(?:\\d[ -]?){13,19}\\b"
      replacement: "[CARD]"
    contact:
      fields: [email, phone]          # any value under these keys, at any depth
  redactions:
    - id: no-cards-to-the-model
      tools: ["crm_*", "db_*"]
      target: result
      redactors: [card-number, contact]
    - id: no-emails-to-search
      tools: ["web_search"]
      target: args
      redactors: [contact]
```

As with policies, a broken rule stops the application at startup and names the property: a missing tool glob, bad
SpEL, a duplicate id, a redactor name that does not exist, or an invalid regular expression.

### The `Redactor` port

`core.spi.Redactor` has one method, so it can be a lambda:

```java
@FunctionalInterface
public interface Redactor {
    JsonValue redact(JsonValue value);
}
```

It works on the core JSON tree (ADR 0002), not on text. A regular expression run over the JSON text of a result
can cut through quotes and escapes and produce invalid JSON, and it cannot see keys, so it could not remove
"whatever is under `ssn`". Two built-ins cover most needs, and both keep the shape of the tree:

- **pattern**: replaces matches in every string value, and in every number whose digits match (card numbers are
  often JSON numbers);
- **fields**: replaces the value under any of the given keys, at any depth, with the replacement string.

`Redactor.strings(UnaryOperator<String>)` builds one from a function over string values, for custom rules.
Application beans of type `Redactor` are available under their bean name.

Redactors must be **deterministic**: the same input gives the same output (design principle 2). This also lets an
investigator who holds the original arguments recompute exactly what the tool received.

### What a call goes through

In `ToolCallGuard.check`, after the access decision and any approval, and only if the call will run:

1. Find every redaction rule that matches. A rule whose condition throws refuses the call and is recorded as
   `EVALUATION_FAILED` with the rule's id (fail closed: a rule that cannot be checked might have been needed).
2. Apply the `ARGS` redactors to the arguments. If one throws, the call is refused and recorded the same way. The
   tool never runs with arguments that were meant to be redacted and were not.
3. Write the receipt, listing the ids of the rules that apply, then return `Verdict.Proceed` with the redacted
   arguments and the `RESULT` redactors to apply.

In `GuardedToolCallback`, the tool is called with the redacted arguments, and its result goes through the
`RESULT` redactors before it is returned to Spring AI. A result that is valid JSON is redacted as a tree; any other
text is redacted as one string value.

If a `RESULT` redactor throws, the tool has **already run**: its side effects cannot be undone. The result is
withheld, and the model gets a structured refusal (`"status":"WITHHELD"`) instead. This is logged as an error and
counted in `countersign.audit.failures` with `stage=redaction`. The receipt is not changed (it is append-only); the
log and the metric record the failure.

### Receipts

- A new field, `redactions`: the ids of the redaction rules that applied, in the order they were applied.
- `argsHmac` stays the HMAC of the arguments **the model proposed**, which are also the ones the policies and the
  approver saw (ADR 0004). Because redactors are deterministic, the arguments the tool received follow from those
  and the rule ids. A second HMAC is not stored.
- **Format rule from now on:** a field added after 0.1.0 is left out of the hashed JSON when it is empty. A receipt
  with no redactions therefore hashes exactly as it did in 0.1.0, and every 0.1.0 receipt keeps verifying with no
  version field. This is a documented exception to ADR 0003's "nulls are written, not omitted", and it is the rule
  for later additions too. A version field will be needed only if a change ever alters how an existing field is
  written.
- `Receipt` keeps its current constructor next to the new canonical one, so code that builds receipts by hand, such
  as an `AuditSink` reading them back, still compiles.
- `countersign-audit-jdbc` adds a `redactions text[] NOT NULL DEFAULT '{}'` column. The schema script adds it to an
  existing table with `ALTER TABLE ... ADD COLUMN IF NOT EXISTS`. Applications that manage the schema with Flyway or
  Liquibase need a migration, and the CHANGELOG says so.

### Observability

`DecisionEvent` gains the applied rule ids, so a `DecisionListener` sees them. A new counter,
`countersign.redactions` (tags `tool`, `rule`, `target`), counts redactions. Tag values stay bounded, because rule
ids come from configuration. `countersign.decisions` does not change.

### Not in this ADR

- Storing raw arguments in receipts, which CLAUDE.md says must be opt-in and go through the `Redactor`. It will
  reuse this port, but it is a separate decision about the receipt format and is left for its own ADR.
- A `@Redact` annotation for tool methods.
- Redacting the prompt or the model's reply. Countersign guards tool calls; it is not a prompt filter.

## Consequences

- Redaction never weakens an access decision and is never dropped by one. The README diagram, the "Decision" list
  in CLAUDE.md and the README features list change: there are three decisions, plus redaction.
- No breaking change to `Decision`, `Decision.Type`, `EvaluationResult.Basis` or the `Receipt` constructor that
  0.1.0 code calls. `Verdict.Proceed` gains components, and gets a constructor with its old signature.
  `DecisionEvent` gains a component in the same way.
- JDBC users who manage the schema themselves must add one column before upgrading. Without it, inserts fail; with
  `countersign.audit.strict=true` the calls are refused, otherwise the failure is logged and counted, as for any
  sink failure.
- A redactor sees every argument or result it applies to, so it must not log what it reads. A regular expression
  runs on data the model controls; a slow pattern on a large result can hold the tool call. The startup check can
  reject only invalid patterns, not slow ones, and the guide will say to keep patterns simple.
- A result redaction cannot stop a tool's side effects; it only controls what the model sees. When that is not
  enough, the right rule is a `deny` or a `require-approval`, not a redaction.
- `Redactor` is a small port and a good first contribution: for example a module with locale-specific detectors
  (national ids, IBANs), each with its own contract test in `countersign-test`.
