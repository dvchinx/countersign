# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Breaking

- `countersign-audit-jdbc` stores a new `redactions` column. `JdbcAuditSink.createSchema` (the default,
  `countersign.audit.jdbc.initialize-schema=true`) adds it to a table created by 0.1.0. If you manage the schema with
  Flyway or Liquibase, run `JdbcAuditSink.ADD_REDACTIONS_COLUMN`
  (`ALTER TABLE countersign_receipt ADD COLUMN IF NOT EXISTS redactions text[] NOT NULL DEFAULT '{}'`) as a migration
  before upgrading; without it every insert fails (refused calls with `countersign.audit.strict=true`, otherwise
  logged and counted).
- `Receipt`, `DecisionEvent` and `Verdict.Proceed` gained a record component (ADR 0015). Their 0.1.0 constructors
  are kept, so code that builds them still compiles; only record deconstruction patterns over them
  (`case Verdict.Proceed(var receipt)`) need the new component.

### Added

- Redaction of tool results ([ADR 0015](docs/adr/0015-redaction.md)). Redaction rules under
  `countersign.redactions` pass a tool's result through named redactors before the model sees it. Unlike policies,
  every rule that matches a call that runs applies, whatever decided access, so an approved call is redacted too and
  no `allow` listed earlier can drop a rule. Built-in redactors, configured under `countersign.redactors`: by
  regular expression (`pattern`, also matching numbers whose digits match) and by key name (`fields`, at any depth).
  A new port, `Redactor`, works on the core JSON tree; `Redactor` beans can be named by their bean name, and
  `RedactionRule` beans are added to the configured rules. A broken rule (unknown redactor, invalid pattern, missing
  target, an id already used by a policy) stops the application at startup and names the property. A rule whose
  condition throws refuses the call; a redactor that throws withholds the result (`"status":"WITHHELD"`), because the
  tool already ran.
- Redaction of tool arguments: a rule with `target: args` (or `args, result`) changes what the tool receives, for
  example to keep a customer's email from an outside search service. Argument redactors run before anyone is asked to
  approve the call; the policies, the approver and the receipt's `argsHmac` keep the arguments the model proposed. The
  tool gets the redacted arguments as compact JSON, or the model's input byte for byte when no rule changed it. A
  redactor that throws, or does not return a JSON object, refuses the call, which is recorded as
  `EVALUATION_FAILED` with the rule's id; the tool never runs. `Verdict.Proceed` carries the arguments in
  `redactedArguments()`.
- Receipts record the redaction rules that applied, in a new `redactions` field. It is left out of the hashed JSON
  when empty, so receipts from 0.1.0 keep verifying with no version field; a test pins two receipts signed by 0.1.0.
- `countersign.redactions` counter (tags `tool`, `rule`, `target`) and `stage=redaction` in
  `countersign.audit.failures` for withheld results. `DecisionEvent.redactions()` and the default method
  `DecisionListener.onResultWithheld` let your own listeners see both.
- `AuditSinkContract` checks that a sink stores a receipt's redactions.
- The `banking-agent` example redacts card numbers. `no-card-numbers-to-the-model` masks them in every tool result,
  so `Show me my cards` returns `number=[CARD]`, and applies to the approved $5,000 transfer too.
  `no-card-numbers-to-the-helpdesk` masks them in the arguments of a new `support_open_ticket` tool, which stands in
  for an outside helpdesk; `GET /helpdesk/tickets` shows what it received. The walkthrough covers both.

- The signed-in Spring Security user is now the principal of each tool call, in policies (`#principal`) and receipts,
  with no code ([ADR 0014](docs/adr/0014-principal-from-spring-security.md)). A new port, `PrincipalResolver`, tells
  `ToolCallGuard` who a call acts for when the adapter does not know; `SpringSecurityPrincipalResolver` implements it
  and the starter registers it when Spring Security is on the classpath. A principal in the tool context still wins.
  A resolver that throws refuses the call (fail closed), and the first call recorded without a principal logs a
  warning. The startup line says where the principal comes from.

- `ReceiptSignerContract` in `countersign-test`: the tests every `ReceiptSigner` must pass. It checks that a
  signature verifies with the public key, does not verify for another hash, passes `ReceiptVerifier` in a chain and
  stays valid under 200 concurrent calls on virtual threads. `Ed25519ReceiptSigner` passes it.

### Fixed

- On Spring AI 2.1, a reply paused for tool call confirmation can be resumed while Countersign is active
  ([#10](https://github.com/dvchinx/countersign/issues/10), [ADR 0013](docs/adr/0013-spring-ai-2.1-without-dropping-2.0.md)).
  The guarded `ToolCallingManager` now implements `executeToolCalls(prompt, response, decisions)` and checks every
  call a person allowed against the policies, so a policy `deny` wins over a person's `allow`. Before, the resume
  failed with `UnsupportedOperationException` and no tool ran. Spring AI 2.0.x stays the baseline and is unaffected.

### Changed

- Redaction is an obligation on calls that run, not a fourth decision
  ([ADR 0015](docs/adr/0015-redaction.md)), so that no approval or policy order can drop it. CLAUDE.md and the
  READMEs no longer describe `REDACT` as a decision.
- The startup line says how many redaction rules are active.
- Starter default: in applications with Spring Security, receipts now name the signed-in user and `#principal`
  policies match it, where before they saw no principal unless the tool context set one (ADR 0014).
- CI runs the full test suite on Spring AI 2.0.2 and 2.1.0. A new, unpublished `compat/spring-ai-2.1` module tests
  tool call confirmation on Spring AI 2.1 in every build.
- `GuardedToolCallingManager` is no longer `final` (its methods are), so that its Spring AI 2.1 variant can extend it.

- The READMEs list the steps of a tool call in the order they happen: the receipt is recorded before the tool runs.
- The tool call diagram shows three decisions plus result redaction, and the receipt recorded and published before
  the tool runs, with its HMAC of the arguments. Its alt text and the steps under it match.
- `CONTRIBUTING.md` and the READMEs list `Redactor` among the ports and `RedactorContract` as its contract test, and
  `CONTRIBUTING.md` describes redaction as an obligation, not a planned `REDACT` decision.
- The release build waits until the Central Portal validates the bundle instead of until it is published, so slow
  publishing no longer fails the release workflow after a successful upload.

## [0.1.0] - 2026-11-24

### Added

- `CONTRIBUTING.md`, `SECURITY.md` (private vulnerability reporting through GitHub), issue templates and a pull
  request template.
- Releases to Maven Central ([ADR 0012](docs/adr/0012-releasing-to-maven-central.md)): a `release` profile that
  attaches sources and javadoc, signs with GPG and publishes through the Central Portal, and a GitHub Actions
  workflow that publishes when a `v*` tag matching the POM version is pushed. Examples are never published.
- The README's demo GIF, recorded from the running banking-agent example with VHS. `examples/banking-agent/demo`
  holds the helper commands and the tape, so the GIF can be re-recorded after any change.
- `banking-agent` example: an agent with balance, transfer and SQL tools over an in-memory bank, guarded with no
  Countersign code in the agent. Transfers above $1,000 wait for a second person through an HTTP approval screen
  (requesters cannot approve their own calls), destructive SQL is denied, receipts can be listed and verified, and
  metrics are exposed through Actuator. A scripted demo model makes it run with no API key.
- Metrics and tracing ([ADR 0011](docs/adr/0011-decision-listeners-and-micrometer.md)): the `DecisionListener` port
  and `DecisionEvent` in the core report every decision without its arguments; `MicrometerDecisionListener` publishes
  `countersign.decisions`, `countersign.evaluation` and `countersign.audit.failures`; `ObservationDecisionListener`
  adds the decision and receipt id to Spring AI's `spring.ai.tool` span. The starter registers them when a
  `MeterRegistry` or `ObservationRegistry` exists, and hands every `DecisionListener` bean to the guard.
- `countersign-audit-jdbc` ([ADR 0010](docs/adr/0010-jdbc-audit-sink.md)): `JdbcAuditSink` stores receipts in an
  append-only PostgreSQL 14+ table, `countersign_receipt` (triggers reject `UPDATE`, `DELETE` and `TRUNCATE`), and
  reads a stream back in sequence order for `ReceiptVerifier`. The starter uses it when the module is on the
  classpath and there is one `DataSource`, creating the table at startup unless
  `countersign.audit.jdbc.initialize-schema=false`.
- `AuditSinkContract` and `TestReceipts` in `countersign-test`: the contract every `AuditSink` extends, and real
  signed receipts for tests.
- `InMemoryAuditSink.stream(streamId)`: one stream in sequence order, ready to verify.
- `countersign-core` domain model: `ToolInvocation`, the sealed `Decision` (`ALLOW`, `DENY`, `REQUIRE_APPROVAL`),
  `EvaluationResult` and an immutable JSON tree for tool arguments
  ([ADR 0002](docs/adr/0002-tool-arguments-as-core-json-tree.md)).
- `PolicyEngine`: policies evaluated in priority order, first match wins, configurable default decision, and
  fail-closed denial when a condition throws.
- `Policy` with tool globs (`db_*`) and pluggable `Condition`s.
- Receipts ([ADR 0003](docs/adr/0003-receipt-format-and-chaining.md)): canonical JSON, a SHA-256 hash chain per
  stream, Ed25519 signatures through the `ReceiptSigner` port (`Ed25519ReceiptSigner`, `java.security` only), and
  `ReceiptVerifier`, which detects edited, deleted, inserted or reordered receipts.
- `GuardedToolCallback` and `GuardedToolCallbackProvider` in `countersign-spring-ai`: every call is parsed
  strictly, checked and recorded before the tool runs, and refused calls return a structured JSON refusal to the
  model. The principal and conversation id come from the `ToolContext` (`countersign.principal`,
  `countersign.conversationId`).
- `@RequiresApproval` ([ADR 0009](docs/adr/0009-requires-approval-as-mandatory-policy.md)): declares on a `@Tool`
  method that its calls need approval. Found at startup by scanning the application's packages, so it covers tool
  objects that are not beans. It becomes a mandatory policy: a configured `allow` cannot skip it and a configured
  `deny` still wins. `PolicyEngine` supports mandatory policies, which can only make a decision stricter.
- Human approval ([ADR 0008](docs/adr/0008-human-approval.md)): the `ApprovalGateway` port, with
  `InMemoryApprovalGateway` for approval screens in the same application. Countersign waits up to
  `countersign.approval.timeout` and refuses the call if the answer is a rejection, never arrives, or cannot be
  obtained. Receipts record the approval outcome and the approver. `ToolCallGuard` is now created with a builder.
- `countersign-spring-boot-starter` ([ADR 0007](docs/adr/0007-starter-defaults.md)): auto-configuration and
  `countersign.*` properties. Policies are written in YAML with sandboxed SpEL conditions and checked at startup,
  with a failure analysis that names the broken property. A startup line reports the policies, the default
  decision, the receipt stream and the audit sink. Missing receipt keys are generated with a warning; every bean
  can be replaced.
- `InMemoryAuditSink`: the default audit sink, keeping the most recent receipts.
- Automatic wrapping ([ADR 0006](docs/adr/0006-automatic-wrapping-via-tool-calling-manager.md)):
  `CountersignToolCallingBeanPostProcessor` decorates Spring AI's `ToolCallingManager` and `ToolCallbackResolver`
  beans with `GuardedToolCallingManager` and `GuardedToolCallbackResolver`, so tools passed with `.tools(...)`,
  `defaultTools(...)`, providers (including MCP clients) and the resolution fallback are all guarded.
- `ToolCallGuard` in `countersign-core`: the framework-free decision path (evaluate, receipt, audit, verdict),
  with the new `AuditSink` port ([ADR 0005](docs/adr/0005-decision-path-in-the-core.md)).
- Receipts store an HMAC-SHA256 of the arguments (`argsHmac`) instead of a plain SHA-256, so values cannot be
  recovered by guessing ([ADR 0004](docs/adr/0004-keyed-hash-for-receipt-arguments.md)).
- `SpelCondition` in `countersign-spring-ai`: SpEL conditions in a read-only `SimpleEvaluationContext` (no type
  references, constructors, bean references, method calls or assignment).
- Multi-module Maven build: `countersign-core`, `countersign-spring-ai`, `countersign-spring-boot-starter`,
  `countersign-audit-jdbc`, `countersign-test`, and the `banking-agent` and `mcp-server-guarded` examples.
- Platform baseline: Java 21, Spring Boot 4.1.2, Spring AI 2.0.2 ([ADR 0001](docs/adr/0001-multi-module-build-and-platform-baseline.md)).
- ArchUnit rule that keeps `countersign-core` free of Spring and other frameworks.
- Maven Wrapper, Spotless (Palantir Java Format), JaCoCo `coverage` profile and GitHub Actions CI.
- Apache 2.0 license, README in English and Spanish.

### Changed

- The READMEs no longer list `REDACT`, the `Redactor` or `@ToolPolicy` as available: they are planned, not
  implemented.
- The README, the banking-agent example and the Javadoc name tools `payments_transfer` and `db_*` instead of
  `payments.transfer` and `db.*`: OpenAI and Anthropic reject tool names with dots. Countersign still accepts any
  name.

### Fixed

- Concurrent tool calls could hand a stream's receipts to the `AuditSink` out of sequence order, while the sink
  contract and `InMemoryAuditSink.receipts()` promised chain order. Sinks now return streams in sequence order when
  read back.

[Unreleased]: https://github.com/dvchinx/countersign/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/dvchinx/countersign/releases/tag/v0.1.0
