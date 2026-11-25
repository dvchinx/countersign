package io.github.dvchinx.countersign.springai;

import io.github.dvchinx.countersign.core.guard.ToolCallGuard;
import java.lang.reflect.InvocationTargetException;
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
 *
 * <p><b>Spring AI 2.1 tool call confirmation.</b> Spring AI 2.1 can pause a batch of tool calls until a person
 * decides on them, and resumes it through a third {@code executeToolCalls} method that takes the decisions. That
 * method's type does not exist in Spring AI 2.0, so this class cannot override it. {@link #guard} and the
 * {@link CountersignToolCallingBeanPostProcessor} therefore return a subclass, compiled against Spring AI 2.1, when
 * the application runs on 2.1 or later: it guards the resumed calls exactly like the others, so a policy that denies
 * a call wins over a person who allowed it. On Spring AI 2.0 that subclass is never loaded. An instance created with
 * the constructor cannot resume: on 2.1 the resume fails with Spring AI's {@code UnsupportedOperationException} and no
 * tool runs.
 *
 * <p>Not designed for extension; it is not {@code final} only for that subclass.
 */
public class GuardedToolCallingManager implements ToolCallingManager {

    /** Compiled against Spring AI 2.1 from {@code src/main/spring-ai-2.1}; see ADR 0013. */
    private static final String RESUMABLE_MANAGER =
            "io.github.dvchinx.countersign.springai.ResumableGuardedToolCallingManager";

    private static final String TOOL_CALL_DECISIONS = "org.springframework.ai.model.tool.ToolCallDecisions";

    private final ToolCallingManager delegate;
    private final Supplier<ToolCallGuard> guard;

    /**
     * Wraps a manager. The result cannot resume calls paused for confirmation on Spring AI 2.1; prefer {@link #guard},
     * which can.
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
        return manager instanceof GuardedToolCallingManager ? manager : create(manager, () -> guard);
    }

    /**
     * Wraps a manager in the variant that matches the Spring AI on the classpath: one that can also resume calls
     * paused for confirmation when Spring AI supports it (2.1 and later), this class otherwise.
     *
     * @param delegate the manager that actually runs tools
     * @param guard supplies the guard; called on every execution
     * @return the guarded manager
     * @throws IllegalStateException if Spring AI supports tool call decisions but the resumable variant cannot be
     *     created, which means the Countersign jar is incomplete; no unguarded manager is returned instead
     */
    static GuardedToolCallingManager create(ToolCallingManager delegate, Supplier<ToolCallGuard> guard) {
        if (!supportsToolCallDecisions()) {
            return new GuardedToolCallingManager(delegate, guard);
        }
        try {
            // By name: this class is compiled against Spring AI 2.0 and cannot see the 2.1 one.
            return Class.forName(RESUMABLE_MANAGER, true, GuardedToolCallingManager.class.getClassLoader())
                    .asSubclass(GuardedToolCallingManager.class)
                    .getDeclaredConstructor(ToolCallingManager.class, Supplier.class)
                    .newInstance(delegate, guard);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            throw new IllegalStateException("Cannot create " + RESUMABLE_MANAGER, e.getCause());
        } catch (ReflectiveOperationException | LinkageError e) {
            throw new IllegalStateException(
                    "Spring AI supports tool call decisions, but Countersign cannot load " + RESUMABLE_MANAGER
                            + "; check that countersign-spring-ai is complete and matches the Spring AI version",
                    e);
        }
    }

    /**
     * Whether the Spring AI on the classpath can resume tool calls with a person's decisions (Spring AI 2.1+).
     *
     * @return {@code true} if {@code ToolCallingManager} declares {@code executeToolCalls(Prompt, ChatResponse,
     *     ToolCallDecisions)}
     */
    static boolean supportsToolCallDecisions() {
        try {
            Class<?> decisions = Class.forName(TOOL_CALL_DECISIONS, false, ToolCallingManager.class.getClassLoader());
            ToolCallingManager.class.getMethod("executeToolCalls", Prompt.class, ChatResponse.class, decisions);
            return true;
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            return false;
        }
    }

    /**
     * Returns the wrapped manager.
     *
     * @return the manager that actually runs tools
     */
    public final ToolCallingManager getDelegate() {
        return delegate;
    }

    @Override
    public final List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions chatOptions) {
        // What the model is offered does not need guarding; what it calls does.
        return delegate.resolveToolDefinitions(chatOptions);
    }

    @Override
    public final ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse chatResponse) {
        return delegate.executeToolCalls(guarded(prompt), chatResponse);
    }

    /**
     * Returns the prompt with its tool callbacks replaced by guarded ones.
     *
     * @param prompt the prompt the wrapped manager would run
     * @return the same prompt if it carries no tools, a copy with guarded tools otherwise
     */
    final Prompt guarded(Prompt prompt) {
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
