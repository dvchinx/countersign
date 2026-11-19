# 2. Tool arguments as a JSON tree owned by the core

- Status: Accepted
- Date: 2026-11-19

## Context

A `ToolInvocation` carries the arguments the model sent, which arrive as a JSON object. Three parts of
Countersign read them:

- **Policy conditions** compare them (`#args['amount'] > 1000`). SpEL indexes `java.util.Map` natively, but not
  a JSON library's node type without extra accessors.
- **Receipts** hash a canonical serialization of them, so the bytes must be fully under our control.
- **Users** build invocations in tests and custom adapters.

`countersign-core` may depend on the JDK, JSpecify and at most one JSON library (CLAUDE.md, ADR 0001). The
obvious choice, Jackson, has two incompatible major versions in use right now: Spring Boot 4 and Spring AI 2
use Jackson 3 (`tools.jackson`), and many applications still carry Jackson 2 (`com.fasterxml.jackson`).
Putting either type in our public model would tie every user to that major version, and would make the
canonical form depend on a third-party serializer's settings.

## Decision

- The core defines its own immutable JSON tree in `core.model`: the sealed interface `JsonValue` with
  `JsonObject`, `JsonArray`, `JsonString`, `JsonNumber`, `JsonBoolean` and `JsonNull`.
- Numbers are `BigDecimal`, so money amounts keep their exact value. Equality is by numeric value
  (`1000` equals `1000.00`).
- `JsonValue.toJava()` converts the tree to unmodifiable `Map`/`List`/`String`/`BigDecimal`/`Boolean`/`null`,
  the form SpEL and other expression languages understand without extra configuration.
- `toString()` on argument types never prints values, so an invocation logged by mistake does not leak data.
- **Parsing stays in the adapters.** `countersign-spring-ai` parses the raw tool input with the Jackson that
  Spring AI already brings and converts it to `JsonValue`. The canonical serializer for receipts lives in
  `core.receipt` and writes `JsonValue` directly.
- `countersign-core` therefore still has **no JSON library dependency**, and `CoreArchitectureTest` is unchanged.

## Consequences

- No version conflict for users, and the core stays dependency-free beyond JSpecify.
- We own a small amount of code (the tree and, later, a canonical writer) that a library would otherwise
  provide. Both are simple and fully covered by tests.
- Adapters pay one tree conversion per tool call. Tool arguments are small, so this is negligible next to a
  model round trip.
- If we ever need a full JSON parser in the core (for example to verify receipts without an adapter), that is a
  new decision and needs its own ADR.
