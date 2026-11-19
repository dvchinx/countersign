# 4. Keyed hash (HMAC-SHA256) for receipt arguments

- Status: Accepted
- Date: 2026-11-19
- Supersedes: the `argsSha256` field of [ADR 0003](0003-receipt-format-and-chaining.md)

## Context

Receipts never store raw arguments; ADR 0003 stored their plain SHA-256 instead. Tool arguments often have few
likely values: an amount between 1 and 10,000 and an account from a short list. Anyone with read access to the
audit store could hash every candidate and find the real values, which defeats the point of not storing them.

## Decision

- Receipts store **`argsHmac`**: HMAC-SHA256 of the UTF-8 canonical JSON of the arguments, as 64 lowercase hex
  characters. It replaces `argsSha256`; the field name is part of the hashed receipt body.
- The key is a secret of at least 32 bytes, configured separately from the signing key
  (`countersign.receipts.args-hmac-key`, Base64). It must be kept out of the audit store.
- `ArgumentsHmac` in `core.receipt` computes it with `javax.crypto.Mac`. `javax.crypto` is part of the JDK
  (`java.base`), so `CoreArchitectureTest` now allows `javax.crypto..` next to `java..`; no dependency is added.
- `ReceiptChain` takes the `ArgumentsHmac` together with the signer. A stream must use one key throughout; rotating
  the key means starting a new stream.

## Consequences

- The argument hash cannot be reversed by guessing without the key.
- Whoever holds the key can still confirm that a given set of arguments matches a receipt (for example, during an
  investigation), by recomputing the HMAC.
- Losing the key does not affect chain or signature verification; it only makes argument matching impossible.
- Applications need a second secret. If it is missing, the starter generates a random key at startup and warns
  (ADR 0007); it never falls back to a plain hash.
