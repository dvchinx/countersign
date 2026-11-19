# 9. `@RequiresApproval` is a mandatory policy, found by scanning the application's packages

- Status: Accepted
- Date: 2026-11-19

## Context

`@RequiresApproval` puts a rule next to the tool it protects. Two questions decide whether that rule can be trusted:

- **How does it combine with configured policies?** The engine's rule is "first match wins". If the annotation
  were an ordinary policy, its place in the order would decide everything. Placed early, it would hide a configured
  `deny` with a condition (a transfer above the daily limit would be sent for approval instead of being denied).
  Placed late, a configured `allow` would silently skip the approval the code asked for.
- **How is it found?** Most applications pass tool objects per request, `chatClient.prompt().tools(new BankTools())`,
  and those objects are never Spring beans. Spring AI's `MethodToolCallback` does not expose its `Method`, so the
  annotation cannot be read at call time without reflecting on Spring AI's private fields.

## Decision

- `PolicyEngine` gains **mandatory policies** next to the ordered ones. All matching mandatory policies are
  evaluated after the ordered ones, and they can only make the outcome stricter
  (`ALLOW` < `REQUIRE_APPROVAL` < `DENY`). On a tie, the ordered policy's decision and reason are kept.
- When no ordered policy matches, the strictest matching mandatory policy decides instead of the default decision.
  A tool that its own code declares as "usable with approval" is therefore usable with approval under the default
  `deny`. Mandatory policies may not `allow`; that would turn them into a way around the default.
- A failing condition in a mandatory policy denies the call, as for ordered policies.
- Each annotated tool becomes one mandatory policy with id `requires-approval.<tool name>`, so receipts show that the
  approval came from code.
- The starter finds annotations **at startup** by reading class files (Spring's ASM-based `MetadataReader`, no class
  loading) in the application's auto-configuration packages, plus any listed in `countersign.annotation-packages`.
  `RequiresApprovalPolicies.from(Class...)` covers applications that list their tool classes explicitly.
- `@RequiresApproval` on a method without `@Tool` stops the application. An annotation that does nothing gives a
  false sense of safety.

## Consequences

- A configuration change cannot remove an approval that the code requires, and a configured deny still applies.
- Annotated tools in packages outside the application's own (for example, in a shared library) are not found unless
  their package is listed in `countersign.annotation-packages`. This is documented next to the property.
- The engine now has two kinds of policies. YAML policies remain ordered; a `mandatory: true` flag for configured
  policies is a natural extension if teams ask for it.
