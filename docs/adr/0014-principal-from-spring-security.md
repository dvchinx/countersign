# 14. The principal from Spring Security

- Status: Accepted
- Date: 2026-11-25

## Context

A receipt answers "what did the agent do, and who allowed it?". The "who" has two parts: the approver, recorded since
ADR 0008, and the principal the agent acts for. Policies can also test `#principal`.

Until now the principal came from one place only: a value the application put in the Spring AI tool context under
`countersign.principal`, on every prompt. An application that forgot it got receipts with no principal and policies on
`#principal` that never matched, with no warning. Most Spring Boot applications that put an agent in front of users
already know who the user is through Spring Security, so asking them to repeat it on every prompt is both friction and
a source of silent gaps in the audit trail.

The signed-in user lives in `SecurityContextHolder`, which is bound to a thread. Spring AI 2.0.2 runs tools on the
request's thread for `ChatClient.call()`, but for `ChatClient.stream()` its `ToolCallingAdvisor` runs them on
`Schedulers.boundedElastic()`, a thread that has no security context unless Reactor's automatic context propagation
is on. Spring Security 7 registers a `ThreadLocalAccessor` for the security context, so with propagation on
(`Hooks.enableAutomaticContextPropagation()`, or `spring.reactor.context-propagation=auto` in Spring Boot) the user
reaches that thread. `PrincipalFromSpringSecurityTest` checks both cases.

## Decision

### A port in the core

`core.spi.PrincipalResolver` has one method, `@Nullable String currentPrincipal()`, so it can be a lambda, and no
framework types, so the core stays framework-free (design principle 5).

`ToolCallGuard.Builder.principalResolver(...)` sets it. The guard asks it only when the invocation has no principal,
before the policies are evaluated, and evaluates, shows the approver and records the invocation with the answer.
A principal the adapter passed explicitly always wins: it is the application saying exactly who the call is for, for
example a service account acting for a batch job.

The guard, not each adapter, asks the port. Every adapter, present and future, gets the same rule and the same failure
handling, and no adapter type had to change.

### Failure semantics

- `null` or a blank name means "unknown": the call is evaluated and recorded with no principal, as before this ADR.
  The guard logs one warning the first time this happens, naming the likely cause (a thread without the security
  context), so a missing principal is no longer silent.
- A resolver that throws refuses the call, before any policy runs and without a receipt, like a receipt that cannot
  be signed. Treating the error as "unknown" would be fail-open: a policy such as `#principal == 'intern'` → `deny`
  would no longer match (design principle 1). For a call already refused for invalid arguments, the error is logged
  and the refusal is recorded without a principal.

No new `EvaluationResult.Basis` value was added for the resolver failure: `basis` is stored in receipts and in the
JDBC table, and adding a value would be a breaking change for code that switches over it.

### Spring Security adapter and starter default

`SpringSecurityPrincipalResolver`, in `countersign-spring-ai` (package `springai.security`), returns the current
`Authentication`'s name. Anonymous and unauthenticated users count as unknown, because an anonymous token's name
(`anonymousUser`) would look like a real user in an audit trail. `spring-security-core` is an optional dependency of
`countersign-spring-ai` and of the starter; applications that do not use Spring Security do not get it.

When Spring Security is on the classpath and `countersign.enabled` is not `false`, the starter registers that resolver,
using the application's `SecurityContextHolderStrategy` bean if there is one. A `PrincipalResolver` bean of the
application's own replaces it. The startup line says where the principal comes from: `principal from tool context,
then SpringSecurityPrincipalResolver` or `principal from tool context only`.

## Consequences

- This changes a starter default (ADR 0007): applications with Spring Security now record the signed-in user without
  any code. Calls that were recorded without a principal now carry one, and `#principal` policies start to match.
  Nothing that was allowed before is denied by the resolver itself; a resolver error is the only new refusal.
- Streaming needs context propagation to carry the user. Without it, receipts of streamed calls have no principal, and
  the one-time warning says why. The tool context key still works everywhere and is documented as the fallback.
- A refusal caused by a failing resolver has no receipt and reaches no `DecisionListener`, so it shows in the error log
  only, not in `countersign.decisions`. A failing resolver is a bug or an outage, which the log already surfaces;
  if it turns out to matter, a dedicated metric can be added without changing the receipt format.
- `PrincipalResolver` is a new port, so it is a natural first contribution for other identity sources (for example a
  request header in a gateway, or an MCP client's identity), each in its own module.
