# Contributing to Countersign

Thanks for helping. Countersign sits between an AI agent and the tools that act on real systems, so the bar is
correctness first: a contribution that is small, tested and fails closed is worth more than a large one that is not.

Code, comments, docs and commit messages are in English. Issues and discussions are welcome in English or Spanish.

## Set up

You need JDK 21 and Docker (for the PostgreSQL integration tests). Nothing else.

```bash
git clone https://github.com/dvchinx/countersign.git
cd countersign
./mvnw verify                      # build and all tests; run it before every push
./mvnw -pl countersign-core test   # fast loop on the domain
./mvnw spotless:apply              # format (Palantir Java Format, checked in CI)
./mvnw -Pcoverage verify           # JaCoCo report in each module's target/site/jacoco
```

`./mvnw verify` must pass on a fresh clone with no extra setup. If it does not, that is a bug: please open an issue.

## Where to start

Countersign grows through its ports (`countersign-core`, package `core.spi`). You can contribute something useful
without touching the policy engine.

| Port               | What an implementation does                               | Contract test in `countersign-test` |
|--------------------|-----------------------------------------------------------|-------------------------------------|
| `AuditSink`        | Stores receipts: Kafka, S3, OpenSearch, a SIEM            | `AuditSinkContract`                 |
| `ApprovalGateway`  | Asks a human to approve a call: Slack, Teams, email, a UI | Not yet: a good first contribution  |
| `ReceiptSigner`    | Signs receipt hashes: KMS, HSM, Vault                     | `ReceiptSignerContract`             |
| `Redactor`         | Masks data in tool calls: national ids, cards, IBANs      | `RedactorContract`                  |
| `DecisionListener` | Observes every decision: metrics, alerts, logs            | Not needed (it cannot change a decision) |

Redaction is not a decision but an obligation on calls that run: every matching redaction rule applies, whatever
decided access ([ADR 0015](docs/adr/0015-redaction.md)).

- **New implementations** live in their own module (`countersign-approval-slack`, `countersign-audit-kafka`, ...).
  Open an issue first so we can agree on the module and its dependencies before you write it.
- **Passing the port's contract test is the bar for merging.** Extend it in your module's tests, as
  `countersign-audit-jdbc` does with `AuditSinkContract`. If the port has no contract yet, writing it is the first PR.
- Issues labelled [`good first issue`](https://github.com/dvchinx/countersign/labels/good%20first%20issue) are small
  and well scoped.

Not planned for now (please discuss before starting): LLM-based classifiers, a UI dashboard, non-Spring frameworks,
A2A, multi-tenant policy management and a remote policy server.

## Rules that are not negotiable

These are the reasons people can trust the library. A PR that breaks one will not be merged, however useful it is.

1. **Fail closed.** Any error in evaluation, approval, signing or audit refuses the call. Never run a tool when the
   decision is unknown. Every new path needs a test that proves it.
2. **No raw arguments by default.** Receipts store an HMAC of the arguments. Never log or persist argument values,
   prompts, tool results or secrets.
3. **Deterministic decisions.** No model calls in the decision path.
4. **`countersign-core` stays framework-free.** Only the JDK, JSpecify and a JSON library. An ArchUnit test enforces
   it; do not weaken it. New dependencies in the core need an ADR.
5. **Policy conditions stay sandboxed.** SpEL runs in a `SimpleEvaluationContext`. No `StandardEvaluationContext`,
   reflection on user input or script engines.

## Code

- Java 21: records, sealed interfaces and pattern matching where they fit. Immutable by default. No Lombok.
- Constructor injection only.
- Every public type and method has Javadoc that explains *why*, not just *what*. The release build fails on Javadoc
  warnings.
- JSpecify nullness: `@NullMarked` packages, `@Nullable` where needed.
- Domain errors extend `CountersignException`. A raw exception must never reach the model as a stack trace.
- Logging through SLF4J, never with argument values. Metric tag values must stay bounded.
- Public API is everything in `core.model`, `core.spi`, the annotations and the `countersign.*` properties. Anything
  else is package-private or in an `internal` package. Breaking the public API needs an ADR and a CHANGELOG entry
  under "Breaking".

## Tests

- Names describe behavior: `deniesTransferAboveLimitWhenNoApproval()`.
- A new policy feature needs a unit test in the core and an integration test through a real `ChatClient` tool call
  in `countersign-spring-ai`, with a stubbed model. Never call a real model API; CI has no keys.
- Each decision type needs a test of its fail-closed path: the evaluator throws, the approval times out, the audit
  sink is down.
- The core keeps at least 90% line coverage. Do not add tests that only chase coverage.

## Pull requests

- One concern per PR, kept small. Suggest unrelated fixes as issues instead of folding them in.
- [Conventional Commits](https://www.conventionalcommits.org/): `feat(core): ...`, `fix(spring-ai): ...`, `docs: ...`,
  `test: ...`, `build: ...`.
- Add a line to `CHANGELOG.md` under `[Unreleased]`.
- If you change the API or the configuration, update `README.md` and `README.es.md` in the same PR. If your Spanish is
  rusty, say so in the PR and a maintainer will update it.
- Significant decisions (a new module, a new dependency, a change in default behavior) get an ADR in `docs/adr/`.
- Never commit secrets, signing keys or `.env` files.

[`CLAUDE.md`](CLAUDE.md) has the full architecture and conventions; it is the same guide AI coding assistants
follow in this repo.

## Security issues

Do not open a public issue for a vulnerability. See [`SECURITY.md`](SECURITY.md).

## License

Countersign is licensed under the [Apache License 2.0](LICENSE). By contributing, you agree that your contributions
are licensed under the same terms.
