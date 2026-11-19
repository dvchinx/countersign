package io.github.dvchinx.countersign.springai;

import io.github.dvchinx.countersign.core.guard.ToolCallGuard;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * A {@link ToolCallingManager} that guards every tool before the wrapped manager runs it.
 *
 * <p>In Spring AI 2.0 every tool call made through a {@code ChatClient} is executed by its {@code ToolCallingAdvisor},
 * which hands it to a {@code ToolCallingManager}. Decorating that one object therefore covers all the ways tools reach
 * a request: {@code .tools(myToolsObject)}, {@code .tools(callback)}, {@code defaultTools(...)}, tool callback
 * providers including MCP clients, and tools added by other advisors. Users do not change their {@code @Tool} methods.
 *
 * <p>On each {@link #executeToolCalls} call, the tool callbacks in the prompt's options are replaced by
 * {@linkplain GuardedToolCallback guarded} versions, then the wrapped manager runs as usual: tool call limits,
 * observations and exception handling stay Spring AI's. Tools found through the manager's
 * {@code ToolCallbackResolver} fallback are not in the options; guard the resolver as well with
 * {@link GuardedToolCallbackResolver} (the {@link CountersignToolCallingBeanPostProcessor} does both).
 *
 * <p>The guard is looked up on each execution, through a supplier, so that this decorator can be created early in the
 * application context without forcing the policy engine and keys to be created with it. If the guard cannot be
 * obtained, the call fails with an exception and no tool runs (fail closed).
 */
public final class GuardedToolCallingManager implements ToolCallingManager {

    private final ToolCallingManager delegate;
    private final Supplier<ToolCallGuard> guard;

    /**
     * Wraps a manager.
     *
     * @param delegate the manager that actually runs tools
     * @param guard supplies the guard; called on every execution
     */
    public GuardedToolCallingManager(ToolCallingManager delegate, Supplier<ToolCallGuard> guard) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.guard = Objects.requireNonNull(guard, "guard");
    }

    /**
     * Wraps a manager with a fixed guard, for a {@code ChatClient} built by hand:
     *
     * <pre>{@code
     * ChatClient.builder(chatModel, observationRegistry, null, null,
     *         ToolCallingAdvisor.builder().toolCallingManager(GuardedToolCallingManager.guard(manager, guard)))
     * }</pre>
     *
     * @param manager the manager that actually runs tools
     * @param guard decides on and records each call
     * @return the guarded manager; the same instance if it is already guarded
     */
    public static ToolCallingManager guard(ToolCallingManager manager, ToolCallGuard guard) {
        Objects.requireNonNull(guard, "guard");
        return manager instanceof GuardedToolCallingManager
                ? manager
                : new GuardedToolCallingManager(manager, () -> guard);
    }

    /**
     * Returns the wrapped manager.
     *
     * @return the manager that actually runs tools
     */
    public ToolCallingManager getDelegate() {
        return delegate;
    }

    @Override
    public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions chatOptions) {
        // What the model is offered does not need guarding; what it calls does.
        return delegate.resolveToolDefinitions(chatOptions);
    }

    @Override
    public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse chatResponse) {
        return delegate.executeToolCalls(guarded(prompt), chatResponse);
    }

    private Prompt guarded(Prompt prompt) {
        if (!(prompt.getOptions() instanceof ToolCallingChatOptions options)) {
            return prompt;
        }
        List<ToolCallback> callbacks = options.getToolCallbacks();
        if (callbacks == null || callbacks.isEmpty()) {
            return prompt;
        }
        ToolCallGuard current =
                Objects.requireNonNull(guard.get(), "No ToolCallGuard available; refusing to run tools");
        List<ToolCallback> guardedCallbacks =
                List.of(GuardedToolCallback.guardAll(current, callbacks.toArray(ToolCallback[]::new)));
        return new Prompt(
                prompt.getInstructions(),
                options.mutate().toolCallbacks(guardedCallbacks).build());
    }
}
