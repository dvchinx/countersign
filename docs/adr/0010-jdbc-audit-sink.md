# 10. PostgreSQL audit sink

- Status: Accepted
- Date: 2026-11-22

## Context

Receipts are only useful if they outlive the process that wrote them. Until now the only sink was
`InMemoryAuditSink`, which loses everything on restart (ADR 0007 announces that with a warning). The MVP promises
an append-only PostgreSQL sink, and the starter has to decide when to use it.

While designing it we found that `ToolCallGuard` chains a receipt under the `ReceiptChain` lock but hands it to the
`AuditSink` after the lock is released. Two concurrent calls can therefore deliver receipts 6 and 5, in that order.
The `AuditSink` Javadoc asked sinks to "keep the order of receipts", which no sink can do if they arrive out of
order, and `InMemoryAuditSink.receipts()` claimed to return the chain order.

## Decision

### Ordering is the reader's job

The guard keeps storing receipts outside the chain lock, so a slow sink never holds up other calls (design
principle 6). Instead, the `AuditSink` contract now says that receipts may arrive out of sequence order and that a
sink must return a stream in sequence order when it is read back. `InMemoryAuditSink.stream(streamId)` sorts;
the JDBC sink orders by its primary key.

### One table, one row per receipt

`countersign-audit-jdbc` ships `JdbcAuditSink` and `schema-postgresql.sql`, which creates `countersign_receipt`:

- one column per receipt field, holding exactly what was hashed and signed, so a stream read back verifies with
  `ReceiptVerifier`. `matchedPolicies` is a `text[]`, the timestamp a `timestamptz` (millisecond receipts survive it
  unchanged), enums are stored by name;
- primary key `(stream_id, sequence_number)`: storing a second receipt with the same sequence fails;
- triggers that reject `UPDATE`, `DELETE` (per row) and `TRUNCATE` (per statement).

The triggers stop accidents. They are not the security boundary: anyone who can disable them can also edit rows,
and that is what signatures and the hash chain detect. Operators who want the triggers to hold should grant the
application role only `INSERT` and `SELECT`.

PostgreSQL 14 or later is required (`CREATE OR REPLACE TRIGGER`). Other databases are out of scope for the MVP.

### Synchronous writes

`append` returns when the row is committed, or throws. That keeps `countersign.audit.strict=true` meaningful: the
call is refused when its receipt was not stored. An asynchronous, batching sink can come later as a separate
implementation if the latency matters.

### Schema creation

`JdbcAuditSink.createSchema(dataSource)` runs the script in one transaction, after taking a transaction-scoped
advisory lock, and only if `countersign_receipt` does not exist. Several instances starting at once take turns, and
an application role without `CREATE` rights can call it once a migration has created the table. The same script
works unchanged in psql, Flyway or Liquibase.

### Starter

`CountersignJdbcAuditAutoConfiguration` applies when `countersign-audit-jdbc` is on the classpath and there is a
single `DataSource`. **Adding the module is the opt-in**; no property is needed. It runs before
`CountersignAutoConfiguration`, so the in-memory sink and its warning go away, and an application `AuditSink` bean
still wins. `countersign.audit.jdbc.initialize-schema` (default `true`) creates the table at startup; a failure
there stops the application instead of leaving receipts with nowhere to go.

### Contract tests

`countersign-test` now holds `AuditSinkContract`, which every sink extends, and `TestReceipts`, which provides real
signed receipts and a matching verifier. Both sinks pass the contract, including 200 concurrent appends that must
read back as one valid stream.

## Consequences

- Receipts survive restarts, and an auditor can read a stream with `JdbcAuditSink.stream(id)` and verify it.
- A tool call now waits for one `INSERT`. With a pooled data source this is a few milliseconds.
- A non-PostgreSQL `DataSource` (for example H2 in tests) with the module on the classpath fails at startup when
  creating the schema. That is loud on purpose; such tests can set `initialize-schema=false` or define their own
  `AuditSink`.
- Truncating the tail of a stream is still not detected by the chain alone (ADR 0003), and with the triggers off a
  superuser can do it. Exporting the last sequence of each stream elsewhere remains the answer.
- There is no retention or partitioning. Large deployments can partition the table by `created_at` themselves;
  the sink only inserts and reads by key.
