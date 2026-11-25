# 13. Supporting Spring AI 2.1 tool call confirmation without dropping 2.0

- Status: Accepted
- Date: 2026-11-25

## Context

Spring AI 2.1.0 adds tool call confirmation. When a tool needs a person's confirmation, `DefaultToolCallingManager`
throws `ToolCallConfirmationRequiredException` and the `ToolCallingAdvisor` returns the reply paused. The application
resumes it through a new method:

```java
default ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse chatResponse, ToolCallDecisions decisions)
```

The default throws `UnsupportedOperationException`. `GuardedToolCallingManager`, compiled against Spring AI 2.0.2, only
overrides the two-argument method, so on 2.1 a paused reply cannot be resumed while Countersign is active (issue #10).
It fails closed, but the two features cannot be used together.

The override needs `ToolCallDecisions`, which does not exist in Spring AI 2.0. `0.1.0` was released a day earlier on
the 2.0 baseline ([ADR 0001](0001-multi-module-build-and-platform-baseline.md)), and moving that baseline to 2.1 would
break every 2.0 user in the first week.

## Decision

Keep Spring AI 2.0.2 as the baseline, and add the override in a class that is only compiled against, and only loaded
on, Spring AI 2.1 or later.

- `countersign-spring-ai/src/main/spring-ai-2.1` holds `ResumableGuardedToolCallingManager`, a package-private
  subclass of `GuardedToolCallingManager` that overrides the three-argument method: it guards the prompt's tools exactly
  like the two-argument method, then hands the decisions to the wrapped manager.
- A second `maven-compiler-plugin` execution compiles that folder into the module's normal output, with Spring AI
  2.1's `spring-ai-model` jar (copied by `maven-dependency-plugin`, version `spring-ai-2.1.version`) placed ahead of
  the baseline one. It passes its own `-classpath`; javac uses the last one given. Everything else in the build is
  compiled against the baseline as before.
- `GuardedToolCallingManager.create` (used by `guard(...)` and by `CountersignToolCallingBeanPostProcessor`) checks
  whether `ToolCallingManager` declares `executeToolCalls(Prompt, ChatResponse, ToolCallDecisions)`. If it does, it
  instantiates the subclass by name; if not, it returns a plain `GuardedToolCallingManager`. Nothing compiled against
  the baseline names the subclass, so Spring AI 2.0 never loads a class that refers to `ToolCallDecisions`. If the
  check passes but the subclass cannot be created, it throws instead of returning a manager that cannot resume.
- `GuardedToolCallingManager` is no longer `final`, so that the subclass can extend it; its methods are `final` and
  the Javadoc says it is not designed for extension. Code that checks `instanceof GuardedToolCallingManager` or calls
  `getDelegate()` keeps working on both lines.
- `compat/spring-ai-2.1`, a module that is never published, overrides the Spring AI BOM with 2.1 and tests the
  confirmation flow through a `ChatClient` and Spring AI's real auto-configuration. It runs in every `./mvnw verify`.
- CI runs the whole suite twice: on the baseline (2.0.2) and on 2.1.0 (`-Dspring-ai.version`).

Semantics, tested in `compat/spring-ai-2.1`:

- A call the person allowed still goes through the policy engine and gets a receipt. A policy `deny` wins over a
  person's `allow`.
- Spring AI's confirmation does not say who confirmed, so it does not count as a Countersign approval: a
  `require-approval` policy still goes to the `ApprovalGateway` (and is refused if there is none).
- A call the person denied never runs, so Countersign records nothing for it.
- Pausing runs no tool. With Countersign active, the pause still happens because `GuardedToolCallback` forwards the
  tool's metadata (`requiresConfirmation`).

## Alternatives considered

- **Move the baseline to Spring AI 2.1.** Simplest code, but it drops 2.0 for everyone one day after `0.1.0`.
  Revisit when a later release raises the baseline anyway; at that point the second compiler run and the reflective
  lookup go away and the override moves into `GuardedToolCallingManager`.
- **A separate published module** compiled against 2.1 and pulled in by the starter. It avoids the second compiler
  run, but adds an artifact users must know about when they do not use the starter, and its POM would have to keep
  Spring AI 2.1 out of their dependency trees.
- **Compile the whole module against 2.1** and declare the baseline only in documentation. The published parent POM
  imports the Spring AI BOM, so users who do not manage Spring AI's version themselves would silently get 2.1.
- **A runtime proxy** (JDK proxy or CGLIB) that intercepts the three-argument method by name. No build change, but it
  loses the `GuardedToolCallingManager` type and is harder to read and to debug.

## Consequences

- Users on Spring AI 2.0.x see no change, and the published POMs still import the 2.0.2 BOM.
- Users on 2.1 can combine native tool call confirmation with Countersign, through the auto-configured manager or
  `GuardedToolCallingManager.guard(...)`. A manager created with the public constructor cannot resume; its Javadoc
  says to use `guard(...)`.
- IDEs do not see `src/main/spring-ai-2.1` as a source folder, because they would compile it against the baseline.
  The file is short; Spotless formats it and CI compiles and tests it.
- The sources and javadoc jars of `countersign-spring-ai` leave out the package-private subclass.
- Supporting a later Spring AI line that adds API Countersign must implement follows the same pattern: a folder, a
  compiler execution, a by-name factory and a `compat` module.
