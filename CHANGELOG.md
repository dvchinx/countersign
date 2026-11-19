# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- `countersign-core` domain model: `ToolInvocation`, the sealed `Decision` (`ALLOW`, `DENY`, `REQUIRE_APPROVAL`),
  `EvaluationResult` and an immutable JSON tree for tool arguments
  ([ADR 0002](docs/adr/0002-tool-arguments-as-core-json-tree.md)).
- `PolicyEngine`: policies evaluated in priority order, first match wins, configurable default decision, and
  fail-closed denial when a condition throws.
- `Policy` with tool globs (`db.*`) and pluggable `Condition`s.
- Receipts ([ADR 0003](docs/adr/0003-receipt-format-and-chaining.md)): canonical JSON, a SHA-256 hash chain per
  stream, Ed25519 signatures through the `ReceiptSigner` port (`Ed25519ReceiptSigner`, `java.security` only), and
  `ReceiptVerifier`, which detects edited, deleted, inserted or reordered receipts.
- `GuardedToolCallback` and `GuardedToolCallbackProvider` in `countersign-spring-ai`: every call is parsed
  strictly, checked and recorded before the tool runs, and refused calls return a structured JSON refusal to the
  model. The principal and conversation id come from the `ToolContext` (`countersign.principal`,
  `countersign.conversationId`).
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
