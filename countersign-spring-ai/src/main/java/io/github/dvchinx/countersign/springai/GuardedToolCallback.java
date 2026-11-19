package io.github.dvchinx.countersign.springai;

import io.github.dvchinx.countersign.core.guard.ToolCallGuard;
import io.github.dvchinx.countersign.core.guard.Verdict;
import io.github.dvchinx.countersign.core.model.Decision;
import io.github.dvchinx.countersign.core.model.JsonObject;
import io.github.dvchinx.countersign.core.model.ToolInvocation;
import io.github.dvchinx.countersign.core.receipt.CanonicalJson;
import java.security.Principal;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/**
 * A {@link ToolCallback} that asks Countersign before running the tool it wraps.
 *
 * <p>This is where enforcement happens: in the call itself, not in the list of tools offered to the model. A tool
 * the model was tricked into calling still has to pass. Each call:
 *
 * <ol>
 *   <li>parses the input strictly (see {@code ToolInputParser}); unreadable input is refused;
 *   <li>runs {@link ToolCallGuard#check}, which evaluates the policies and records a signed receipt;
 *   <li>runs the wrapped tool only on {@link Verdict.Proceed}, passing it the original input and context unchanged.
 * </ol>
 *
 * <p>A refusal is returned to the model as a small JSON object it can explain to the user, for example
 * {@code {"decision":"DENY","invocationId":"…","reason":"Destructive SQL is not allowed from agents",
 * "status":"REFUSED","tool":"db.query"}}. It never contains a stack trace, argument values or internal errors. Any
 * unexpected failure in Countersign is also a refusal (fail closed). Exceptions thrown by the tool itself are passed
 * on untouched, as if Countersign were not there.
 *
 * <p>The principal and conversation id recorded in receipts are read from the {@link ToolContext}, under
 * {@link #PRINCIPAL_KEY} and {@link #CONVERSATION_ID_KEY}. A {@link Principal} value is recorded by its name; any
 * other value by {@code toString()}.
 */
public final class GuardedToolCallback implements ToolCallback {

    /** {@link ToolContext} key for who the agent acts for, recorded in receipts. */
    public static final String PRINCIPAL_KEY = "countersign.principal";

    /** {@link ToolContext} key for the conversation id, recorded in receipts. */
    public static final String CONVERSATION_ID_KEY = "countersign.conversationId";

    private static final Logger LOG = LoggerFactory.getLogger(GuardedToolCallback.class);

    private static final String UNEXPECTED_FAILURE = "The call was blocked because Countersign could not check it";

    private final ToolCallback delegate;
    private final ToolCallGuard guard;

    private GuardedToolCallback(ToolCallback delegate, ToolCallGuard guard) {
        this.delegate = delegate;
        this.guard = guard;
    }

    /**
     * Wraps a tool. A tool that is already guarded is returned as is, so wrapping twice does not check twice.
     *
     * @param tool the tool to guard
     * @param guard decides on and records each call
     * @return the guarded tool
     */
    public static ToolCallback guard(ToolCallback tool, ToolCallGuard guard) {
        Objects.requireNonNull(tool, "tool");
        Objects.requireNonNull(guard, "guard");
        return tool instanceof GuardedToolCallback ? tool : new GuardedToolCallback(tool, guard);
    }

    /**
     * Wraps several tools, for example the result of {@code ToolCallbacks.from(myTools)}.
     *
     * @param guard decides on and records each call
     * @param tools the tools to guard
     * @return the guarded tools, in the same order
     */
    public static ToolCallback[] guardAll(ToolCallGuard guard, ToolCallback... tools) {
        Objects.requireNonNull(tools, "tools");
        return Arrays.stream(tools).map(tool -> guard(tool, guard)).toArray(ToolCallback[]::new);
    }

    /**
     * Returns the wrapped tool.
     *
     * @return the tool this callback guards
     */
    public ToolCallback getDelegate() {
        return delegate;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        return call(toolInput, null);
    }

    @Override
    public String call(String toolInput, @Nullable ToolContext toolContext) {
        String toolName = delegate.getToolDefinition().name();
        String invocationId = UUID.randomUUID().toString();
        Verdict verdict;
        try {
            verdict = check(toolName, invocationId, toolInput, toolContext);
        } catch (RuntimeException e) {
            // The guard itself never throws for a decision failure; this catches bugs and misconfiguration.
            LOG.error(
                    "Countersign could not check tool '{}' ({}); refusing the call",
                    toolName,
                    e.getClass().getName());
            LOG.debug("Countersign failure details", e);
            return refusal(toolName, invocationId, Decision.Type.DENY, UNEXPECTED_FAILURE);
        }
        return switch (verdict) {
            case Verdict.Proceed proceed -> delegate.call(toolInput, toolContext);
            case Verdict.Refuse refuse -> refusal(toolName, invocationId, refuse.decision(), refuse.reason());
        };
    }

    private Verdict check(
            String toolName, String invocationId, @Nullable String toolInput, @Nullable ToolContext toolContext) {
        String principal = principalOf(toolContext);
        String conversationId = contextValue(toolContext, CONVERSATION_ID_KEY);
        Instant now = Instant.now();
        JsonObject arguments = ToolInputParser.parse(toolInput);
        if (arguments == null) {
            LOG.warn("Tool '{}' was called with input that is not a single JSON object; refusing the call", toolName);
            return guard.refuseInvalidArguments(toolName, invocationId, principal, conversationId, now);
        }
        return guard.check(new ToolInvocation(invocationId, toolName, arguments, principal, conversationId, now));
    }

    private static @Nullable String principalOf(@Nullable ToolContext toolContext) {
        if (toolContext != null && toolContext.getContext().get(PRINCIPAL_KEY) instanceof Principal principal) {
            return principal.getName();
        }
        return contextValue(toolContext, PRINCIPAL_KEY);
    }

    private static @Nullable String contextValue(@Nullable ToolContext toolContext, String key) {
        if (toolContext == null) {
            return null;
        }
        Object value = toolContext.getContext().get(key);
        return value == null ? null : value.toString();
    }

    private static String refusal(String toolName, String invocationId, Decision.Type decision, String reason) {
        return CanonicalJson.write(JsonObject.builder()
                .put("status", "REFUSED")
                .put("tool", toolName)
                .put("decision", decision.name())
                .put("reason", reason)
                .put("invocationId", invocationId)
                .build());
    }

    @Override
    public String toString() {
        return "GuardedToolCallback[" + delegate.getToolDefinition().name() + "]";
    }
}
