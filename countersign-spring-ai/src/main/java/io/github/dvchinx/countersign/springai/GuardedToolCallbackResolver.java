package io.github.dvchinx.countersign.springai;

import io.github.dvchinx.countersign.core.guard.ToolCallGuard;
import java.util.Objects;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.resolution.ToolCallbackResolver;

/**
 * A {@link ToolCallbackResolver} whose resolved tools are {@linkplain GuardedToolCallback guarded}.
 *
 * <p>Spring AI can resolve a tool by name when the model calls one that was not in the request's tool list (the
 * resolution fallback, off by default since Spring AI 2.0.1 because of CVE-2026-59318). Such a tool never passes
 * through the request options, so {@link GuardedToolCallingManager} cannot see it; guarding the resolver closes that
 * path. The guard is looked up on each resolution.
 */
public final class GuardedToolCallbackResolver implements ToolCallbackResolver {

    private final ToolCallbackResolver delegate;
    private final Supplier<ToolCallGuard> guard;

    /**
     * Wraps a resolver.
     *
     * @param delegate the resolver that finds tools
     * @param guard supplies the guard; called on every resolution that finds a tool
     */
    public GuardedToolCallbackResolver(ToolCallbackResolver delegate, Supplier<ToolCallGuard> guard) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.guard = Objects.requireNonNull(guard, "guard");
    }

    @Override
    public @Nullable ToolCallback resolve(String toolName) {
        ToolCallback tool = delegate.resolve(toolName);
        if (tool == null) {
            return null;
        }
        return GuardedToolCallback.guard(
                tool, Objects.requireNonNull(guard.get(), "No ToolCallGuard available; refusing to run tools"));
    }
}
