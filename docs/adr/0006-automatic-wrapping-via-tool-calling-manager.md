# 6. Automatic wrapping by decorating the ToolCallingManager

- Status: Accepted
- Date: 2026-11-19

## Context

Users should get enforcement without touching their `@Tool` methods (CLAUDE.md, "Integration points"). The first
idea was a `BeanPostProcessor` that wraps `ToolCallback` and `ToolCallbackProvider` beans. In Spring AI 2.0 that
misses the most common case: `chatClient.prompt().tools(myToolsObject)` builds its callbacks per request, from an
object that is not a tool bean, so no post-processor ever sees them.

Reading the Spring AI 2.0.2 sources shows where every tool call does pass:

- `ChatClient` runs tools in its `ToolCallingAdvisor`, which hands each round to a `ToolCallingManager`.
- The auto-configured `ChatClient.Builder` gets that manager from the `ToolCallingManager` bean
  (`ToolCallingAutoConfiguration` → `ChatClientAutoConfiguration`).
- Chat models only call `resolveToolDefinitions`; they no longer execute tools themselves.
- A tool that was not offered in the request can still be found by name through the `ToolCallbackResolver`
  bean, when the resolution fallback is enabled.

## Decision

- `CountersignToolCallingBeanPostProcessor` wraps:
  - the `ToolCallingManager` bean in `GuardedToolCallingManager`, which replaces the tool callbacks in each
    prompt's options with guarded ones and then lets Spring AI run them as usual (limits, observations,
    exception handling);
  - the `ToolCallbackResolver` bean in `GuardedToolCallbackResolver`, which closes the resolution-fallback path.
- `ToolCallback` and `ToolCallbackProvider` beans are **not** replaced. Replacing them would change their
  runtime type, which breaks injection by concrete class and Spring AI's special handling of MCP client providers
  (detected by class name and kept lazy).
- The `ToolCallGuard` is resolved lazily through an `ObjectProvider`, on the first tool call. The processor can
  therefore be a `static` bean registered early, without creating the policy engine or loading keys at that point.
- If no guard can be obtained, the tool call fails with an exception and the tool does not run (fail closed).
- The starter registers the processor; this module only provides it.

## Consequences

- `.tools(...)`, `defaultTools(...)`, tool callback providers including MCP clients, and fallback-resolved tools
  are all guarded with no change to user code. This is tested against Spring AI's real auto-configuration.
- Not covered automatically, and documented: a `ChatClient` built by hand with its own manager
  (`ChatClient.create(model)`); wrap its manager with `GuardedToolCallingManager.guard(...)`. Tool callbacks that
  application code calls directly; wrap them with `GuardedToolCallback.guard(...)`. Tools served by an MCP
  *server* are executed by the server, not by a `ToolCallingManager`; they are handled in the MCP step.
- Code that injects the concrete `DefaultToolCallingManager` or `DelegatingToolCallbackResolver` type will fail
  once Countersign is active. Injecting the interfaces, which is what Spring AI itself does, keeps working.
- If Spring AI moves tool execution elsewhere in a later release, this decorator is the one place to revisit.
