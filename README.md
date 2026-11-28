<h1 align="center">Countersign</h1>

<p align="center">
  <b>Your agent proposes. Countersign decides.</b>
</p>

<p align="center">
  Runtime guardrails for Spring AI agents: policy-enforced, human-approved, signed tool calls (incl. MCP).<br/>
  Fail-closed, auditable. One starter dependency, no Python sidecar.
</p>

<p align="center">
  <img alt="Status: pre-alpha" src="https://img.shields.io/badge/status-pre--alpha-orange">
  <a href="https://central.sonatype.com/artifact/io.github.dvchinx/countersign-spring-boot-starter"><img alt="Maven Central" src="https://img.shields.io/maven-central/v/io.github.dvchinx/countersign-spring-boot-starter"></a>
  <a href="https://github.com/dvchinx/countersign/actions/workflows/ci.yml"><img alt="CI" src="https://github.com/dvchinx/countersign/actions/workflows/ci.yml/badge.svg"></a>
  <img alt="Java 21" src="https://img.shields.io/badge/java-21-blue?logo=openjdk&logoColor=white">
  <img alt="Spring AI 2.0 and 2.1" src="https://img.shields.io/badge/spring%20ai-2.0.x%20%7C%202.1.x-6DB33F?logo=spring&logoColor=white">
  <img alt="License: Apache 2.0" src="https://img.shields.io/badge/license-Apache%202.0-blue">
  <a href="https://github.com/dvchinx/countersign/stargazers"><img alt="GitHub stars" src="https://img.shields.io/github/stars/dvchinx/countersign?style=social"></a>
</p>

<p align="center"><b>English</b> · <a href="README.es.md">Español</a></p>

---

In law, a *countersignature* is the second signature that makes a document valid.
Countersign adds that second signature to your AI agent's actions: every tool call the model
proposes is checked against your policies, sent to a human when it needs one, and recorded in an
Ed25519-signed, hash-chained **receipt** before anything runs.

Think of it as **Spring Security for agent tool calls**. It works with Spring AI `@Tool` methods,
`ToolCallback`s and MCP tools, and you don't have to change your tools.

## Demo

The [`banking-agent`](examples/banking-agent) example, recorded as it runs:

<p align="center">
  <img src="docs/images/banking-demo.gif" width="860"
       alt="Terminal recording of the banking agent demo. Alice asks the agent to move $5,000 to ACME Corp; Countersign holds the payments_transfer call for a second person. Alice cannot approve her own request; ops@bank.example approves it and the transfer runs. Its receipt notes that the no-card-numbers-to-the-model redaction rule applied. Alice then asks the agent to drop the transactions table; Countersign denies it under the no-destructive-sql policy. She asks to see her cards: the query runs, and the card numbers reach the model as [CARD]. She asks for a support ticket with her card number in it: the outside helpdesk receives [CARD] instead. Each call leaves a signed receipt, and the four receipts are listed, chained, and verified.">
</p>

[Still diagram of the same flow](docs/images/banking-demo.drawio.svg).

The model never sees a stack trace. It gets a structured refusal it can explain to the user, and
you get a receipt you can verify later.

Try it yourself in two minutes, with no API key and no Docker: `./mvnw -pl examples/banking-agent -am install
-DskipTests && ./mvnw -pl examples/banking-agent spring-boot:run`, then follow the
[example's walkthrough](examples/banking-agent/README.md).

## Quickstart

> **Pre-alpha:** `0.1.0` is the first release. APIs may still change before `1.0`; every breaking change is listed
> in the [CHANGELOG](CHANGELOG.md).

**1. Add the starter**

```xml
<dependency>
  <groupId>io.github.dvchinx</groupId>
  <artifactId>countersign-spring-boot-starter</artifactId>
  <version>0.1.0</version>
</dependency>
```

**2. Write a few policies** in `application.yml`

```yaml
countersign:
  default-decision: deny                 # fail closed: anything not allowed is blocked
  policies:
    - id: no-destructive-sql
      tools: ["db_*"]
      when: "#args['query'] matches '(?i).*\\b(drop|truncate|delete)\\b.*'"
      decision: deny
    - id: transfers-need-approval
      tools: ["payments_transfer"]
      when: "#args['amount'] > 1000"
      decision: require-approval
```

**3. That's it.** Your existing tools are wrapped automatically:

```java
@Tool(name = "payments_transfer", description = "Transfer money between accounts")
@RequiresApproval(reason = "Moves real money")   // optional: per-tool policy as an annotation
public TransferResult transfer(String from, String to, BigDecimal amount) { ... }
```

On startup you'll see one line telling you Countersign is active and which default it uses.

## What happens on a tool call

<p align="center">
  <img src="docs/images/tool-call-flow.drawio.svg" width="860"
       alt="Tool call flow: the agent proposes a call, GuardedToolCallback asks the PolicyEngine, which returns ALLOW, REQUIRE APPROVAL or DENY. Every decision, with the human's answer when approval was needed, produces a signed receipt before the tool runs, sent to the AuditSink and Micrometer. Allowed and approved calls pass their arguments through every matching redaction rule, run the tool, and pass its result through every matching rule; denied, rejected or timed-out calls get a structured refusal. Either one goes back to the model. A note says that redaction is not a decision: every matching rule applies to every call that runs, approved calls included.">
</p>

1. **Build** a `ToolInvocation`: tool name, parsed arguments, principal, conversation id.
2. **Evaluate** it against your policies, in priority order. The first match wins; if nothing matches, the default (`deny`) applies. On `REQUIRE_APPROVAL`, wait for a human until the timeout.
3. **Record** a receipt: the decision, the matched policies, who approved it, the redaction rules that apply, a keyed hash (HMAC-SHA256) of the arguments (never the raw values), the previous receipt's hash, and an Ed25519 signature.
4. **Publish** it to your `AuditSink`, along with Micrometer metrics and observations.
5. **Apply** the decision: run the tool if it was allowed or approved, with its arguments and its result passed
   through every redaction rule that matches; otherwise return a structured refusal.

The receipt is signed before the tool runs: if signing fails, the tool does not run.

Enforcement happens **where the tool runs**, not in the list of tools sent to the model. A tool the
model was never offered, or one it was tricked into calling, still has to pass the policy engine.

## How it compares

Countersign does not filter prompts or model output. It governs **actions**. Use it alongside a
content filter, not instead of one.

|                                             | **Countersign** | Prompt / output filters | Python guardrail sidecar | `if` checks inside each `@Tool` |
|---------------------------------------------|:---------------:|:-----------------------:|:------------------------:|:-------------------------------:|
| Enforces at the tool call itself            | ✅              | ❌                      | ⚠️ depends on wiring     | ✅                              |
| Runs in your JVM, no extra service          | ✅              | ✅                      | ❌                       | ✅                              |
| Covers MCP tools                            | ✅              | ❌                      | ⚠️                       | ❌ (not your code)              |
| Human approval built in                     | ✅              | ❌                      | ⚠️                       | ❌ roll your own                |
| Signed, hash-chained audit trail            | ✅              | ❌                      | ❌                       | ❌                              |
| Deterministic (no LLM in the decision path) | ✅              | ⚠️ often LLM-based      | ⚠️ often LLM-based       | ✅                              |
| Fail-closed by default                      | ✅              | ⚠️                      | ⚠️                       | ⚠️ easy to forget               |
| Policies separate from business code        | ✅              | ✅                      | ✅                       | ❌                              |

## Features

- **Three decisions:** `ALLOW`, `DENY`, `REQUIRE_APPROVAL`.
- **Redaction:** mask card numbers, emails or any field in a tool's result before it reaches the model, or in its
  arguments before they reach the tool. Every matching rule applies, even to approved calls
  ([ADR 0015](docs/adr/0015-redaction.md)).
- **Declarative policies:** tool globs (`db_*`), priorities, and conditions written in a sandboxed SpEL
  (`SimpleEvaluationContext`: no type references, no bean access, no arbitrary method calls).
- **Annotations:** `@RequiresApproval` on your `@Tool` methods. `@ToolPolicy(id = "...")` is planned.
- **Human in the loop:** a pluggable `ApprovalGateway`. Countersign waits up to a timeout, denies if nobody answers, and records the approver in the receipt.
- **Who asked:** with Spring Security, the signed-in user is the call's principal, in policies (`#principal`) and in the
  receipt, with no code. Any other source plugs in through `PrincipalResolver`.
- **Tamper-evident receipts:** canonical JSON, a SHA-256 hash chain and Ed25519 signatures using only
  `java.security`, plus a verification utility that detects any edited or deleted receipt.
- **Privacy by default:** receipts store a keyed hash (HMAC) of the arguments, so values cannot be recovered by guessing. Raw values are never stored.
- **Audit sinks:** in-memory and PostgreSQL (append-only) out of the box.
- **Observability:** `countersign.decisions`, `countersign.evaluation`, `countersign.redactions` and
  `countersign.audit.failures` via Micrometer,
  and the decision and receipt id on each tool call's trace span.
- **Test helpers:** `countersign-test` lets you assert in your own tests that your agent was blocked.

### A receipt

```json
{
  "streamId": "banking-agent-7c1e",
  "sequence": 42,
  "invocationId": "3f6c2a9e-8d41-4b7a-9f0e-5c2d1b8a7e64",
  "tool": "payments_transfer",
  "argsHmac": "9b2f4c…e71a",
  "decision": "REQUIRE_APPROVAL",
  "reason": "Moves real money",
  "basis": "POLICY_MATCHED",
  "matchedPolicies": ["transfers-need-approval"],
  "approval": "APPROVED",
  "approver": "bob",
  "principal": "alice",
  "conversationId": "conv-7",
  "timestamp": "2026-11-18T14:02:11.482Z",
  "previousHash": "1b42…08aa",
  "hash": "7f3a…c91e",
  "signature": "q3C1…Ag=="
}
```

## Configuration

All properties live under `countersign.*`. Only `policies` is needed to get started.

| Property | Default | What it does |
|---|---|---|
| `countersign.enabled` | `true` | `false` turns Countersign off; startup logs a warning that tools are not guarded. |
| `countersign.default-decision` | `deny` | Decision when no policy matches: `deny`, `allow` or `require-approval`. Anything but `deny` is logged as a warning. |
| `countersign.default-reason` | `No policy allows this call` | Reason the model gets when the default decision refuses a call. |
| `countersign.policies[n].id` | | Unique id, recorded in receipts. |
| `countersign.policies[n].tools` | | Tool name globs: `db_*`, `payments_transfer`. |
| `countersign.policies[n].when` | always | Sandboxed SpEL over `#args`, `#tool`, `#principal`, `#conversationId`. |
| `countersign.policies[n].decision` | | `allow`, `deny` or `require-approval`. |
| `countersign.policies[n].reason` | generic | Shown to the model (deny) or the approver (require-approval). |
| `countersign.policies[n].priority` | `0` | Lower runs first; ties keep the order listed. |
| `countersign.redactors.<name>.pattern` | | Regular expression replaced in string values, and in numbers whose digits match. |
| `countersign.redactors.<name>.fields` | | Keys whose values are replaced, at any depth, ignoring case. Set `pattern` or `fields`. |
| `countersign.redactors.<name>.replacement` | `[REDACTED]` | Literal replacement text. |
| `countersign.redactions[n].id` | | Unique id (shared with policy ids), recorded in receipts. |
| `countersign.redactions[n].tools` | | Tool name globs. |
| `countersign.redactions[n].when` | always | Sandboxed SpEL over the arguments, as in policies. |
| `countersign.redactions[n].target` | | `args` (what the tool receives), `result` (what the model gets back), or `args, result`. |
| `countersign.redactions[n].redactors` | | Redactors to apply, in order: keys of `countersign.redactors` or `Redactor` bean names. |
| `countersign.receipts.signing-key` | generated | Ed25519 private key, PKCS#8, Base64. |
| `countersign.receipts.args-hmac-key` | generated | 32+ random bytes, Base64, for the keyed hash of arguments. |
| `countersign.receipts.stream-id` | app name + random | Receipt chain id for this instance. |
| `countersign.audit.strict` | `false` | Refuse the call when its receipt cannot be stored. |
| `countersign.audit.in-memory-capacity` | `10000` | Receipts kept by the default in-memory sink. |
| `countersign.audit.jdbc.initialize-schema` | `true` | With `countersign-audit-jdbc`: create the receipts table at startup if it is missing. |
| `countersign.approval.timeout` | `2m` | How long a call waits for a human answer before it is denied. |
| `countersign.annotation-packages` | | Extra packages scanned for `@RequiresApproval`; your application's packages are always scanned. |

A broken policy or redaction rule (missing tool, bad SpEL, duplicate id, unknown redactor, invalid pattern) stops the
application at startup with a message that names the property. A typo never leaves a tool silently unguarded.

**Tool names.** OpenAI and Anthropic only accept tool names made of letters, digits, `_` and `-` (up to 64
characters), so name a tool `payments_transfer`, not `payments.transfer`. Countersign accepts any name, including the
dotted names some MCP servers use, and `*` in a glob matches any characters.

**Keys for production.** Without keys, Countersign generates them at startup and says so in a warning. Receipts
from that run cannot be verified after a restart. Generate real keys once and pass them as secrets:

```bash
openssl genpkey -algorithm ed25519 -outform DER | base64 -w0   # COUNTERSIGN_SIGNING_KEY
openssl rand -base64 32                                         # COUNTERSIGN_ARGS_HMAC_KEY
```

```yaml
countersign:
  receipts:
    signing-key: ${COUNTERSIGN_SIGNING_KEY}
    args-hmac-key: ${COUNTERSIGN_ARGS_HMAC_KEY}
```

**Storing receipts.** Receipts are kept in memory by default, and startup warns about it. To keep them in
PostgreSQL (14 or later), add the JDBC module next to the starter. With a `DataSource` in the application, it is
used automatically, and the `countersign_receipt` table is created at startup if it is missing:

```xml
<dependency>
    <groupId>io.github.dvchinx</groupId>
    <artifactId>countersign-audit-jdbc</artifactId>
</dependency>
```

The table only accepts inserts: triggers reject `UPDATE`, `DELETE` and `TRUNCATE`. If you manage the schema with
Flyway or Liquibase, copy `schema-postgresql.sql` from the jar and set
`countersign.audit.jdbc.initialize-schema=false`. Upgrading from 0.1.0 adds a `redactions` column: with
`initialize-schema=true` it is added at startup; otherwise add this migration first, or every insert fails:

```sql
ALTER TABLE countersign_receipt ADD COLUMN IF NOT EXISTS redactions text[] NOT NULL DEFAULT '{}';
``` To check a stream later:

```java
ReceiptVerifier verifier = ReceiptVerifier.fromX509Base64(publicKey);
verifier.verify(jdbcAuditSink.stream(streamId));   // Valid, or the first receipt that was changed
```

To send receipts somewhere else, define your own `AuditSink` bean. Any Countersign bean can be replaced the same
way, and `Policy` beans are added to the configured policies.

**Metrics and tracing.** With Spring Boot Actuator, Countersign publishes to Micrometer with no configuration:

| Meter | Tags | Use it to |
|---|---|---|
| `countersign.decisions` | `tool`, `decision`, `policy`, `basis`, `outcome`, `approval` | Alert on `outcome=refuse`; see approvals that time out. |
| `countersign.evaluation` | `tool` | Watch policy evaluation time (waiting for a human is not included). |
| `countersign.redactions` | `tool`, `rule`, `target` | See which redaction rules apply to which tools. |
| `countersign.audit.failures` | `tool`, `stage` | Catch receipts that were not signed (`receipt`) or not stored (`sink`), and results withheld because they could not be redacted (`redaction`). |

Traces show `countersign.decision`, `countersign.outcome`, `countersign.policy` and `countersign.receipt` on Spring
AI's `spring.ai.tool` span. To react to decisions yourself, define a `DecisionListener` bean: it gets every decision,
without the arguments.

**`@RequiresApproval` in code.** The rule lives next to the tool it protects, so no configuration change can drop it.
A configured `allow` cannot skip the approval, a configured `deny` still wins, and with no configured policy for the
tool it is usable with approval even under the default `deny`. Countersign finds the annotation at startup by
scanning your application's packages, so it also works for tool objects that are not beans
(`.tools(new BankTools())`). On a method without `@Tool`, it stops the application instead of doing nothing.

**Human approval.** A `require-approval` call waits for an `ApprovalGateway` bean, up to
`countersign.approval.timeout`. Without a gateway it is denied at once, and startup warns about it. For an approval
screen inside your app, use the in-memory gateway and answer with the identity of the logged-in user:

```java
@Bean
InMemoryApprovalGateway approvals() {
    return new InMemoryApprovalGateway();
}

// In your approval controller:
approvals.pending();                                // calls waiting, with tool, arguments and reason
approvals.approve(invocationId, currentUser());     // the tool runs; the receipt names the approver
approvals.reject(invocationId, currentUser(), "Unknown payee");
```

The agent's request waits while the approval is pending, so keep the timeout below any HTTP timeout in front of it.

**Redaction.** A call can be allowed and still carry data that should not go where it is going. A redaction rule
changes the **result** before the model gets it, such as a card number from a CRM, or the **arguments** before the tool
gets them, such as a customer's email sent to an outside search service. Unlike policies, every rule that matches a
call that runs applies, whatever decided access: an approved call is redacted too, and no `allow` listed earlier can
skip a rule.

```yaml
countersign:
  policies:
    - id: crm-reads
      tools: ["crm_*"]
      decision: allow
    - id: searches
      tools: ["web_search"]
      decision: allow
  redactors:
    card-number:
      pattern: "\\b\\d(?:[ -]?\\d){12,18}\\b"   # in strings, and in numbers whose digits match
      replacement: "[CARD]"
    email:
      pattern: "[\\w.+-]+@[\\w-]+(?:\\.[\\w-]+)+"
      replacement: "[EMAIL]"
    contact:
      fields: [email, phone]                    # the value under these keys, at any depth
  redactions:
    - id: no-cards-to-the-model
      tools: ["crm_*"]
      target: result                            # args, result, or both: "args, result"
      redactors: [card-number, contact]
    - id: no-emails-to-search
      tools: ["web_search"]
      target: args
      redactors: [email]
```

From `crm_find_customer`, the model gets `{"name":"Ana Pérez","email":"[REDACTED]","phone":"[REDACTED]","card":"[CARD]"}`.
When the model asks `web_search` for `orders of ana@example.com`, the tool receives `orders of [EMAIL]`. Each receipt
lists the rules that applied, such as `"redactions": ["no-cards-to-the-model"]`. For your own rules, define a
`Redactor` bean and name it in `redactors`; `Redactor.strings(text -> ...)` is the shortest way to write one. Keep
patterns simple: they run on data the model controls, and the call waits for them.

*Arguments* are redacted before anyone is asked to approve the call. The policies, the approver and the receipt's
argument hash keep what the model proposed, and redactors are deterministic, so whoever holds the original arguments
can recompute what the tool received. The tool gets the redacted arguments as compact JSON, or the model's input
unchanged when no rule changed anything. If an argument redactor fails, the call is refused and recorded, and the
tool never runs. Check that the tool still accepts what it gets: a number replaced with `[REDACTED]` becomes a string.

*Results* that are JSON are redacted as a tree, so `fields` sees their keys; any other text is redacted as one string.
If a result redactor fails, the tool has already run, so its result is withheld: the model gets
`"status":"WITHHELD"`, and `countersign.audit.failures` counts it with `stage=redaction`. Redaction only controls what
data moves; when a tool must not run at all, use `deny` or `require-approval`.

**Who the agent acts for.** Each receipt records the principal, and policies can test it (`#principal == 'alice'`).
With Spring Security in the application, it is the signed-in user, with no code; anonymous users count as unknown. To
set it yourself, for example for a batch job, put it in the tool context, which always wins:

```java
chatClient.prompt(question)
        .toolContext(Map.of(GuardedToolCallback.PRINCIPAL_KEY, "nightly-reconciliation"))
        .call();
```

The security context is bound to a thread. With `.stream()`, Spring AI runs tools on a Reactor thread, which only
carries the user with `spring.reactor.context-propagation=auto`; otherwise the call is recorded without a principal
and Countersign warns once. To read the principal from somewhere else, define a `PrincipalResolver` bean. If it
throws, the call is refused.

**What is guarded.** Every tool run by a `ChatClient` from the auto-configured `ChatClient.Builder`, however the tool
was registered: `.tools(myTools)`, `defaultTools(...)`, `ToolCallbackProvider`s (MCP clients included), or Spring
AI's resolution by name. A `ChatClient` you build yourself with `ChatClient.create(model)` has its own tool manager:
wrap it with `GuardedToolCallingManager.guard(...)`, or inject `ChatClient.Builder` instead.

## Design principles

1. **Fail closed.** If policy evaluation, approval, signing or auditing fails, the call is denied. A tool is never run silently after an error.
2. **Deterministic.** The same input and the same policies give the same decision. There are no LLM calls in the decision path.
3. **Private by default.** Raw prompts, arguments and results are never logged or persisted unless you opt in.
4. **No surprises.** With no policies, the only effect is the documented default (`deny`), and the startup log says so.
5. **Framework-free core.** `countersign-core` depends only on the JDK, JSpecify and a JSON library, and an ArchUnit test enforces it.
6. **Low overhead.** Policy evaluation never blocks on I/O.

## Modules

| Module                            | What it is                                                         |
|-----------------------------------|--------------------------------------------------------------------|
| `countersign-core`                | Pure Java domain: model, policy engine, receipts, SPIs             |
| `countersign-spring-ai`           | `GuardedToolCallback` and automatic wrapping of tools              |
| `countersign-spring-boot-starter` | Auto-configuration and `countersign.*` properties                  |
| `countersign-audit-jdbc`          | Append-only PostgreSQL `AuditSink`                                 |
| `countersign-test`                | Test helpers and SPI contract tests                                |
| `examples/banking-agent`          | Flagship demo: an agent that reads balances and moves money        |
| `examples/mcp-server-guarded`     | Guarding tools exposed through an MCP server                       |
| `compat/spring-ai-2.1`            | Tests on Spring AI 2.1 (tool call confirmation); not published     |

**Requirements:** Java 21, Spring Boot 4.x, Spring AI 2.0.1 or later. Tested on Spring AI 2.0 and 2.1. On 2.1,
Spring AI's tool call confirmation works with Countersign: a call a person confirms still goes through your policies,
and a policy `deny` wins over the person's `allow`.

## Contributing

Countersign is meant to grow through its SPIs. You can make a useful contribution without
touching the policy engine:

- **`ApprovalGateway`:** Slack, Teams, email or a web console approver
- **`AuditSink`:** Kafka, S3, OpenSearch, or your SIEM
- **`ReceiptSigner`:** KMS / HSM-backed signing
- **`Redactor`:** detectors for national ids, IBANs or secrets in tool calls
- **`PrincipalResolver`:** who the agent acts for, from a gateway header, a JWT or an MCP client's identity

Passing the port's contract test in `countersign-test` is the bar for merging (`AuditSinkContract`,
`RedactorContract` and `ReceiptSignerContract` today; contracts for the other ports are good first
contributions). Start with
[`CONTRIBUTING.md`](CONTRIBUTING.md); issues labelled `good first issue` are the best place to begin. To report a
vulnerability, see [`SECURITY.md`](SECURITY.md).

If Countersign looks useful to you, a ⭐ helps other Spring teams find it.

## License

Apache License 2.0. See [`LICENSE`](LICENSE).
