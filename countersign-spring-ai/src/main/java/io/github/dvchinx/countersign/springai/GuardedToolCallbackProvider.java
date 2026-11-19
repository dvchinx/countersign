package io.github.dvchinx.countersign.springai;

import io.github.dvchinx.countersign.core.guard.ToolCallGuard;
import java.util.Objects;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;

/**
 * A {@link ToolCallbackProvider} whose tools are all {@linkplain GuardedToolCallback guarded}.
 *
 * <p>Wrapping happens each time the tools are requested, not once at construction, because some providers, such as
 * MCP clients, change their tool list at runtime. A tool added later is guarded like the others.
 */
public final class GuardedToolCallbackProvider implements ToolCallbackProvider {

    private final ToolCallbackProvider delegate;
    private final ToolCallGuard guard;

    /**
     * Wraps a provider.
     *
     * @param delegate the provider whose tools to guard
     * @param guard decides on and records each call
     */
    public GuardedToolCallbackProvider(ToolCallbackProvider delegate, ToolCallGuard guard) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.guard = Objects.requireNonNull(guard, "guard");
    }

    @Override
    public ToolCallback[] getToolCallbacks() {
        return GuardedToolCallback.guardAll(guard, delegate.getToolCallbacks());
    }
}
