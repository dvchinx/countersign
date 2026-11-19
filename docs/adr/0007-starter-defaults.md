# 7. Starter defaults: generated keys, in-memory receipts, one stream per instance

- Status: Accepted
- Date: 2026-11-19

## Context

The README promises a quickstart that works copy-pasted in under five minutes with at most ten lines of
configuration, and that quickstart has no keys and no database. At the same time, Countersign must fail closed
and must never surprise the user (design principles 1 and 4). The starter has to choose what happens when the
user has configured only policies.

## Decision

- **Policies from YAML.** `countersign.policies` is turned into `Policy` objects at startup, with `when` parsed as
  sandboxed SpEL. Any invalid policy stops the application, with the property path in the message and a failure
  analysis that says what to fix. `Policy` beans are added after the configured ones.
- **Default decision `deny`**, as before. Any other value is allowed but logged as a warning at every startup.
- **Missing keys are generated, loudly.** Without `countersign.receipts.signing-key` the starter generates an
  Ed25519 key pair, logs a warning, and logs the public key so receipts from that run can still be verified.
  Without `countersign.receipts.args-hmac-key` it generates 32 random bytes and warns. Enforcement is unaffected:
  every call is still evaluated, signed and recorded. A configured key that cannot be read stops the application;
  it never falls back to a generated key.
- **Receipts in memory by default** (`InMemoryAuditSink`, last 10,000), announced with a warning. A user
  `AuditSink` bean replaces it.
- **One stream per running instance.** The default stream id is the application name plus 64 random bits, so two
  instances, or two runs, never extend the same chain.
- **A startup line** states that Countersign is active, the number of policies, the default decision, the stream
  and the audit sink. With `countersign.enabled=false`, a warning says tool calls are not guarded.
- Every bean is `@ConditionalOnMissingBean`, so any part can be replaced.

## Consequences

- The quickstart works with no secrets and no database, and its YAML is tested by reading it from `README.md`.
- A production deployment that forgets its keys still enforces policies, but its receipts are not verifiable
  across restarts. The warnings make that visible. A future `countersign.receipts.require-keys=true` could turn the
  warning into a startup failure for teams that want that guarantee.
- Receipts from separate runs form separate streams. Verifying a whole history means verifying each stream.
