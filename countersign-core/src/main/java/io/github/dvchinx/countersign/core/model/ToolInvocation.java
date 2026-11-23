package io.github.dvchinx.countersign.core.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One tool call proposed by the model, as the policy engine sees it.
 *
 * <p>Built by an adapter before the tool runs, and never changed afterwards: the same invocation is evaluated,
 * shown to an approver and recorded in the receipt, so all three see exactly the same call.
 *
 * <p>{@link #toString()} lists argument names only, never values (design principle 3).
 *
 * @param id unique id of this invocation, carried into its receipt
 * @param toolName the tool's name as the model called it, for example {@code payments_transfer}
 * @param arguments the parsed arguments
 * @param principal who the agent acts for, or {@code null} if the adapter does not know
 * @param conversationId the conversation the call belongs to, or {@code null} if the adapter does not know
 * @param timestamp when the invocation was received
 */
public record ToolInvocation(
        String id,
        String toolName,
        JsonObject arguments,
        @Nullable String principal,
        @Nullable String conversationId,
        Instant timestamp) {

    /**
     * Creates an invocation.
     *
     * @throws IllegalArgumentException if {@code id} or {@code toolName} is blank
     */
    public ToolInvocation {
        requireNonBlank(id, "id");
        requireNonBlank(toolName, "toolName");
        Objects.requireNonNull(arguments, "arguments");
        Objects.requireNonNull(timestamp, "timestamp");
    }

    /**
     * Creates an invocation with a random id, the current time, and no principal or conversation.
     *
     * <p>Handy in tests and for callers that have no request context. Adapters should use the canonical
     * constructor so the principal and conversation end up in the receipt.
     *
     * @param toolName the tool's name
     * @param arguments the parsed arguments
     * @return a new invocation
     */
    public static ToolInvocation of(String toolName, JsonObject arguments) {
        return new ToolInvocation(UUID.randomUUID().toString(), toolName, arguments, null, null, Instant.now());
    }

    private static void requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
