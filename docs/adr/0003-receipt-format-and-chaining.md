# 3. Receipt format, hash chaining and signing

- Status: Accepted; the arguments hash is superseded by [ADR 0004](0004-keyed-hash-for-receipt-arguments.md)
- Date: 2026-11-19

## Context

Receipts must let an auditor answer "what did the agent do, and who allowed it?" and prove that the answer was
not changed afterwards (design principle 7). That needs three things to be fixed exactly, because any ambiguity
makes two honest implementations compute different hashes:

- the bytes that get hashed,
- how receipts are linked,
- what gets signed, and with which algorithm.

## Decision

### Fields

A receipt (`core.model.Receipt`) holds, in this order: `streamId`, `sequence`, `invocationId`, `tool`,
`argsSha256`, `decision`, `reason`, `basis`, `matchedPolicies`, `principal`, `conversationId`, `timestamp`,
`previousHash`, `hash`, `signature`. Raw arguments are never stored; `argsSha256` is the SHA-256 of their
canonical JSON.

### Canonical JSON

Implemented in `core.receipt.CanonicalJson`, close to RFC 8785 (JCS):

- no whitespace; object members sorted by name, comparing UTF-16 code units;
- strings escape only `"`, `\` and characters below U+0020 (`\b \t \n \f \r` short forms, others as lowercase
  `\u00xx`); lone surrogates are escaped as `\uxxxx` so that no two strings share an encoding;
- **numbers differ from JCS:** they are written as `BigDecimal.stripTrailingZeros().toString()` (zero as `0`), so
  `1000` and `1000.00` both become `1E+3`. JCS formats numbers as IEEE doubles, which would change large or
  precise amounts and break the guarantee that equal arguments hash equally and different ones do not;
- UTF-8 encoding.

### Hash and chain

- The **hash** is the SHA-256 of the canonical JSON of every field except `hash` and `signature`, written as
  64 lowercase hex characters. `null` fields are written as JSON `null`, not omitted.
- `timestamp` is truncated to milliseconds and written as `uuuu-MM-dd'T'HH:mm:ss.SSS'Z'` (UTC, always three
  fraction digits), so a receipt read back from any database still produces the same hash.
- Receipts are chained **per stream**: `previousHash` is the hash of the previous receipt in the same stream, and
  the first receipt uses 64 zeros (`Receipt.GENESIS_HASH`). `sequence` starts at 1 and has no gaps.
- A stream is written by one `ReceiptChain`, which serializes appends with a lock. Typically each application
  instance owns one stream id; a chain can resume from the last stored receipt.

### Signature

- **Ed25519** over the raw 32 bytes of the hash, through the `ReceiptSigner` port. The signature is stored as
  standard Base64. The default signer uses `java.security` only (JDK 15+); no crypto dependency.
- If signing fails, no receipt is produced, the chain does not advance and the tool call is denied.

### Verification

`ReceiptVerifier` checks one stream, in order: same stream id, contiguous sequence from 1, `previousHash` links,
recomputed hash equals the stored text exactly, and the signature is valid for the given public key. It returns
the first receipt that fails and why.

## Consequences

- Editing, deleting, inserting or reordering a stored receipt is detected. Tests alter every field of a stored
  receipt and check that verification catches each change.
- **Truncation of the tail is not detected by the chain alone.** A stream with its last receipts removed is still
  a valid, shorter stream. Detecting that needs the last sequence number kept somewhere the writer cannot change
  (for example, exported periodically). This is a known limit, not a bug.
- **`argsSha256` is a plain hash.** For arguments with few possible values (a small amount and a known account),
  someone who has the receipt can find the values by trying candidates. A keyed hash (HMAC with a secret kept out
  of the audit store) would prevent that; it is a candidate for a later ADR, before `0.1.0` if possible.
- Key rotation is not modelled yet: a verifier takes one public key. Rotating keys means starting a new stream.
- The format has no version field. Changing it before `0.1.0` is free; after that, any change needs a new ADR
  and a version marker so that old receipts keep verifying.
