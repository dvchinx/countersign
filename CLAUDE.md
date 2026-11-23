# CLAUDE.md

Guidance for Claude Code (and human contributors) working in this repository.

## Project overview

**Countersign** is a runtime guardrails layer for AI agents built with **Spring AI**.
Think of it as *"Spring Security for agent tool calls"*: every tool an agent invokes (Spring AI
`@Tool` methods, `ToolCallback`s, and MCP tools) passes through a policy engine that can
**allow**, **deny**, **require human approval**, or **redact** the call, and every decision
produces a tamper-evident **receipt** that can be audited and replayed.

Target user: a Spring Boot team that wants to put an agent in production without writing a
Python sidecar, and that has to answer "what did the agent do, and who allowed it?"

Value in one line: add one starter dependency, write a few policies, get enforcement + audit trail.

### Name and positioning

The name says what the library does: in law, a *countersignature* is the second signature that
makes a document valid. Here the agent proposes a tool call, and Countersign adds the second
signature: a policy decision, a human approval when needed, and an Ed25519-signed receipt.

- Tagline: **"Your agent proposes. Countersign decides."**
- GitHub description (keep the keywords, they drive search): *"Runtime guardrails for Spring AI
  agents: policy-enforced, human-approved, signed tool calls (incl. MCP). Fail-closed, auditable."*
- GitHub topics: `spring-ai`, `spring-boot`, `java`, `ai-agents`, `guardrails`, `mcp`,
  `llm-security`, `agent-security`, `tool-calling`, `human-in-the-loop`, `audit-log`.
- Say "guardrails for tool calls", not just "guardrails": in the Java world "guardrails" also
  means input/output filters (LangChain4j) and code quality gates. We are not a prompt filter.
- Naming rules: the project and its artifacts never start with `spring` (Spring trademark, and
  Spring Boot reserves `spring-boot-starter-*`; third-party starters are `{name}-spring-boot-starter`).
  Use "for Spring AI" in prose. The repo may later be proposed to the `spring-ai-community` org.

### Current status

Pre-alpha. We are building the MVP (see "MVP scope" below). Public APIs may still change, but
every change to a public type must be deliberate (see "Public API rules").

## Tech stack

- Java 21 (baseline; use records, sealed interfaces, pattern matching, virtual threads where useful)
- Spring Boot 4.x, Spring Framework 7.x
- Spring AI 2.0.x, minimum 2.0.1 (tool calling + MCP support in core; 2.0.1 fixes CVE-2026-59318,
  where a tool not offered to the model could still be dispatched. That CVE is exactly why
  enforcement must happen where the tool runs, not in the tool list sent to the model)
- MCP Java SDK 2.0.x (transitively via Spring AI)
- Maven (multi-module, use the wrapper `./mvnw`)
- Testing: JUnit 5, AssertJ, Mockito, ArchUnit, Testcontainers (PostgreSQL)
- Observability: Micrometer Observation API
- Nullability: JSpecify annotations (`@NullMarked` at package level, `@Nullable` where needed)
> Spring AI evolves fast. Before implementing against a Spring AI or MCP SDK type, check the
> actual API of the version declared in the root `pom.xml` (sources jar or official docs).
> Do not rely on memory of older 1.x APIs.

## Repository layout

```
countersign/
├── countersign-core/                  # Pure Java domain. NO Spring, NO Spring AI dependencies.
│   └── io.github.dvchinx.countersign.core
│       ├── model/                  # ToolInvocation, Decision, PolicyMatch, Receipt...
│       ├── policy/                 # Policy, PolicyEngine, condition evaluation
│       ├── receipt/                # Receipt building, hash chaining, signing
│       └── spi/                    # Ports: AuditSink, ApprovalGateway, Redactor, ReceiptSigner
├── countersign-spring-ai/             # Adapters: wraps ToolCallback / ToolCallbackProvider
├── countersign-spring-boot-starter/   # Auto-configuration + @ConfigurationProperties
├── countersign-audit-jdbc/            # AuditSink implementation (PostgreSQL, append-only)
├── countersign-test/                  # Test helpers for users (assert an agent was blocked, etc.)
├── examples/
│   ├── banking-agent/              # Flagship demo: agent that can read balances and move money
│   └── mcp-server-guarded/         # Guarding tools exposed through an MCP server
└── docs/
    ├── adr/                        # Architecture Decision Records (NNNN-title.md)
    └── guides/
```

## Architecture

Hexagonal. The domain lives in `countersign-core` and talks to the outside world only through
interfaces in `core.spi`. Spring and Spring AI are adapters.

Request flow for a single tool call:

```
Agent (ChatClient) ──► GuardedToolCallback.call(input, ctx)                (countersign-spring-ai)
                          │
                          ├─ 1. Parse input strictly, build ToolInvocation (tool name, args, principal,
                          │     conversation id). Unreadable input → refused and recorded.
                          └─ ToolCallGuard.check(invocation) ──► Verdict           (countersign-core)
                               ├─ 2. PolicyEngine.evaluate(invocation) ──► EvaluationResult
                               ├─ 3. REQUIRE_APPROVAL only: ApprovalGateway, wait up to the timeout;
                               │     rejected / timed out / no gateway / error → refuse (ADR 0008)
                               ├─ 4. ReceiptChain.append ──► signed Receipt, with approval outcome and
                               │     approver (before the tool runs)
                               ├─ 5. AuditSink.append(receipt) + Micrometer observation
                               └─ 6. Apply decision:
                                      ALLOW / approved → delegate to the real ToolCallback
                                      DENY / not approved → structured refusal (no exception leak)
                                      REDACT           → Redactor transforms args and/or result, then delegate
```

### Key types (core)

- `ToolInvocation` (record): tool name, arguments (as a JSON tree), principal, conversation id, timestamp.
- `Decision` (sealed interface): `Allow`, `Deny(reason)`, `RequireApproval(reason)`, `Redact(rules)`.
- `Policy`: id, target tools (glob), condition, decision, priority.
- `PolicyEngine`: evaluates policies in priority order; first match wins; if nothing matches,
  applies the configured default (`deny` by default).
- `Receipt` (record): invocation id, tool, args HMAC-SHA256 (ADR 0004), decision, matched policy ids, approval
  outcome and approver (ADR 0008), timestamp, `previousHash`, `hash`, `signature`.

### Integration points (spring-ai module)

- Decorate tool callbacks instead of modifying them: `GuardedToolCallback` implements
  `ToolCallback` and delegates to the original.
- Wrapping is automatic: `CountersignToolCallingBeanPostProcessor` decorates Spring AI's
  `ToolCallingManager` and `ToolCallbackResolver` beans, so every tool a `ChatClient` executes is
  guarded, however it was registered, and users do not change their `@Tool` methods (ADR 0006).
- Annotations for per-tool policy, read at startup:
  - `@RequiresApproval(reason = "...")`: found by scanning the application's packages (ASM, no class
    loading), so it covers tool objects that are not beans. Becomes a mandatory policy: it can only make
    the decision stricter (ADR 0009).
  - `@ToolPolicy(id = "...")` to attach a named policy from configuration.

### Configuration (starter)

Prefix `countersign.*`. Example:

```yaml
countersign:
  enabled: true
  default-decision: deny          # fail closed
  receipts:
    signing-key: ${COUNTERSIGN_SIGNING_KEY}   # Ed25519 private key (PKCS#8, base64)
    args-hmac-key: ${COUNTERSIGN_ARGS_HMAC_KEY}  # >= 32 random bytes, base64; keep it out of the audit store
  policies:
    - id: no-destructive-sql
      tools: ["db.*"]
      when: "#args['query'] matches '(?i).*\\b(drop|truncate|delete)\\b.*'"
      decision: deny
      reason: "Destructive SQL is not allowed from agents"
    - id: transfers-need-approval
      tools: ["payments.transfer"]
      when: "#args['amount'] > 1000"
      decision: require-approval
```

Conditions use SpEL evaluated with a **`SimpleEvaluationContext` (read-only, no type
references, no bean access, no method invocation on arbitrary types)**. Never use
`StandardEvaluationContext` for policy conditions: that would be a code-injection vector.

## Design principles (non-negotiable)

1. **Fail closed.** Any error in policy evaluation, approval, signing, or audit results in DENY,
   never in silently executing the tool. Make this behavior explicit and tested.
2. **Deterministic decisions.** No LLM calls in the decision path in the MVP. Same input,
   same policies → same decision.
3. **Never log or persist raw arguments by default.** Receipts store a keyed hash (HMAC-SHA256) of the arguments.
   Persisting raw args is opt-in and goes through the `Redactor`.
4. **Zero surprise for users.** Adding the starter with no policies must not break an app
   except by the documented default (`deny`). Startup logs must say clearly that Countersign is
   active and which default it uses.
5. **Core stays framework-free.** `countersign-core` must compile with only the JDK, JSpecify and
   a JSON library. This is enforced by an ArchUnit test; do not weaken it.
6. **Low overhead.** Policy evaluation must not block on I/O. Audit writes may be async, but a
   failed write after the decision must surface as a metric and log error (and, with
   `countersign.audit.strict=true`, fail the call).
7. **Tamper evidence.** Receipts are hash-chained per stream (SHA-256 of canonical JSON +
   previous hash) and signed with Ed25519 (`java.security`, no extra crypto dependency).

## Commands

```bash
./mvnw verify                          # build + all tests (run before every commit)
./mvnw -pl countersign-core test          # fast loop on the domain
./mvnw -pl examples/banking-agent spring-boot:run
./mvnw spotless:apply                  # format
./mvnw -Pcoverage verify               # JaCoCo report in target/site/jacoco
```

Integration tests that need PostgreSQL use Testcontainers; Docker must be running.

## Code conventions

- Package root: `io.github.dvchinx.countersign`.
- Prefer records and sealed interfaces for domain types; immutable by default.
- No Lombok (this is a library; keep the bytecode and the IDE experience predictable).
- Constructor injection only. No field injection.
- Every public type and method has Javadoc that explains *why*, not just *what*.
- Formatting is enforced by Spotless (Palantir Java Format). Do not hand-format against it.
- Exceptions: domain errors are `CountersignException` subclasses; never let a raw
  `RuntimeException` from a policy reach the model as a stack trace.
- Logging: SLF4J, structured where possible, never include argument values or secrets.
- Metrics: `countersign.decisions` (tags: `tool`, `decision`, `policy`), `countersign.evaluation`
  (timer), `countersign.audit.failures` (counter).

## Testing rules

- Every new policy feature needs: a unit test in core, and an integration test through a real
  `ChatClient` tool call in `countersign-spring-ai` (use a stubbed model, never a real API key in CI).
- Every decision type must have a test proving the fail-closed path (what happens if the
  evaluator throws, the approval times out, the audit sink is down).
- Receipt chain: test that modifying any stored receipt is detected by verification.
- Test names describe behavior: `deniesTransferAboveLimitWhenNoApproval()`.
- Core module target: ≥ 90% line coverage. Do not add tests that only chase coverage.

## Public API rules

- Public API = everything in `core.model`, `core.spi`, annotations, and `countersign.*` properties.
- Anything else should be package-private or live in an `internal` package.
- Breaking a public API requires an ADR in `docs/adr/` and a CHANGELOG entry under "Breaking".
- Keep SPIs small: one method per port when possible, so users can implement them with a lambda.

## Documentation

- The README quickstart must work copy-pasted in under 5 minutes. If you change the API or
  config, update the README in the same PR.
- Examples are part of the build and must compile and pass their tests in CI.
- Significant decisions (new module, new dependency, change in default behavior) get an ADR.
- Code, comments, docs and commit messages are in **English**. A Spanish README
  (`README.es.md`) is maintained in parallel for the LATAM community.
- README order (the first screen decides whether someone stars the repo): name + tagline,
  badges (Maven Central, CI, license, Java 21), the demo GIF, a quickstart of ≤ 10 lines of
  config, the "what happens on a tool call" diagram, the comparison table, then everything else.
  Keep the GitHub description, topics and README first paragraph in sync with "Name and positioning".

## Contributors

The project grows through its SPIs. Design so that a newcomer can contribute something useful
without touching the policy engine:

- Every port in `core.spi` (`AuditSink`, `ApprovalGateway`, `Redactor`, `ReceiptSigner`) is a
  natural first contribution. New implementations live in their own module
  (`countersign-approval-slack`, `countersign-audit-kafka`, ...) and are post-MVP unless agreed.
- Each SPI ships with an abstract contract test in `countersign-test` (e.g.
  `AuditSinkContract`) that a new implementation extends; passing it is the bar for merging.
- Keep `CONTRIBUTING.md`, issue templates and a `good first issue` label current. When you find
  a small, well-scoped task while working, suggest it as a `good first issue` instead of
  silently fixing it in an unrelated PR.
- `./mvnw verify` must pass on a fresh clone with only JDK 21 and Docker. No hidden setup.

## Git and PRs

- Conventional Commits: `feat(core): ...`, `fix(spring-ai): ...`, `docs: ...`, `test: ...`.
- Small PRs, one concern each. Update `CHANGELOG.md` (Keep a Changelog format).
- Do not commit secrets, signing keys, or `.env` files. Example keys in `examples/` must be
  clearly marked as demo-only and generated at startup when possible.

## Dependencies

- New runtime dependencies in `countersign-core` are not allowed without an ADR.
- In other modules, prefer what Spring Boot already manages. Check the Spring Boot BOM before
  adding a version.

## MVP scope

Done means: published to Maven Central as `0.1.0` with the banking demo and a recorded GIF.

- [x] `countersign-core`: model, `PolicyEngine` (first match wins, default decision), SpEL conditions with `SimpleEvaluationContext`
- [x] Receipts: canonical JSON, SHA-256 chain, Ed25519 signing, verification utility
- [x] `countersign-spring-ai`: `GuardedToolCallback` + automatic wrapping of `@Tool` methods
- [x] Decisions: ALLOW, DENY, REQUIRE_APPROVAL (with default deny-on-timeout gateway)
- [x] `@RequiresApproval` annotation
- [x] Starter: auto-config, `countersign.*` properties, startup banner line
- [x] `AuditSink`: in-memory + JDBC/PostgreSQL
- [ ] Micrometer metrics and observations
- [ ] Example `banking-agent` (blocks a large transfer, asks approval, shows receipt)
- [ ] README with quickstart, GIF, comparison table

Out of scope for MVP (do not start without discussion): LLM-based classifiers, UI dashboard,
non-Spring frameworks, A2A protocol, multi-tenant policy management, remote policy server.

## What NOT to do

- Do not execute a tool when the decision is unknown or an error occurred.
- Do not add Spring or Spring AI imports to `countersign-core`.
- Do not use `StandardEvaluationContext`, reflection on user input, or script engines for policies.
- Do not store raw prompts, tool arguments, or tool results unless the user explicitly enabled it.
- Do not call real model APIs in tests.
- Do not change default behavior (e.g., `default-decision`) without an ADR.

## Glossary

- **Tool call / invocation**: one execution of a tool requested by the model.
- **Policy**: rule that matches invocations and produces a decision.
- **Decision**: ALLOW, DENY, REQUIRE_APPROVAL, REDACT.
- **Receipt**: signed, hash-chained record of one decision; the unit of audit.
- **Approval gateway**: port that asks a human (or system) to approve a pending invocation.
