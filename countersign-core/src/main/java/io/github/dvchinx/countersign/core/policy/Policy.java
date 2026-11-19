package io.github.dvchinx.countersign.core.policy;

import io.github.dvchinx.countersign.core.model.Decision;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * A rule: when the model calls one of {@link #tools()} and {@link #condition()} holds, apply {@link #decision()}.
 *
 * <p>Policies are evaluated by {@link PolicyEngine} in ascending {@link #priority()} order, so a lower number runs
 * first, the same convention as Spring's {@code @Order}. Policies with the same priority keep the order in which
 * they were given to the engine.
 *
 * @param id unique id, recorded in receipts and metrics; letters, digits, {@code .}, {@code _} and {@code -}
 * @param tools the tools this policy applies to; at least one
 * @param condition when the policy applies, once a tool pattern has matched
 * @param decision what to do with a call this policy applies to
 * @param priority evaluation order; lower runs first
 */
public record Policy(String id, List<ToolPattern> tools, Condition condition, Decision decision, int priority) {

    /** The priority used when none is given. */
    public static final int DEFAULT_PRIORITY = 0;

    private static final Pattern ID = Pattern.compile("[A-Za-z0-9._-]+");

    /**
     * Creates a policy.
     *
     * @throws InvalidPolicyException if the id is not valid or no tool pattern is given
     */
    public Policy {
        Objects.requireNonNull(id, "id");
        if (!ID.matcher(id).matches()) {
            throw new InvalidPolicyException(
                    "Policy id must be non-empty and use only letters, digits, '.', '_' and '-': '" + id + "'");
        }
        tools = List.copyOf(tools);
        if (tools.isEmpty()) {
            throw new InvalidPolicyException("Policy '" + id + "' must target at least one tool");
        }
        Objects.requireNonNull(condition, "condition");
        Objects.requireNonNull(decision, "decision");
    }

    /**
     * Starts building a policy.
     *
     * @param id the policy id
     * @return a new builder
     */
    public static Builder builder(String id) {
        return new Builder(id);
    }

    /**
     * Tells whether this policy targets a tool, ignoring the condition.
     *
     * @param toolName the tool's name
     * @return {@code true} if any of the tool patterns matches
     */
    public boolean targets(String toolName) {
        for (ToolPattern tool : tools) {
            if (tool.matches(toolName)) {
                return true;
            }
        }
        return false;
    }

    /** Builds a {@link Policy}. The condition defaults to {@link Condition#always()}. Not thread-safe. */
    public static final class Builder {

        private final String id;
        private final List<ToolPattern> tools = new ArrayList<>();
        private Condition condition = Condition.always();
        private @Nullable Decision decision;
        private int priority = DEFAULT_PRIORITY;

        private Builder(String id) {
            this.id = id;
        }

        /**
         * Adds tool patterns.
         *
         * @param globs globs such as {@code db.*}
         * @return this builder
         * @throws InvalidPolicyException if a glob is not valid
         */
        public Builder tools(String... globs) {
            for (String glob : globs) {
                tools.add(ToolPattern.of(glob));
            }
            return this;
        }

        /**
         * Sets the condition.
         *
         * @param condition when the policy applies
         * @return this builder
         */
        public Builder when(Condition condition) {
            this.condition = Objects.requireNonNull(condition, "condition");
            return this;
        }

        /**
         * Sets the decision. Required.
         *
         * @param decision what to do with a call this policy applies to
         * @return this builder
         */
        public Builder decision(Decision decision) {
            this.decision = Objects.requireNonNull(decision, "decision");
            return this;
        }

        /**
         * Sets the priority. Defaults to {@link Policy#DEFAULT_PRIORITY}.
         *
         * @param priority evaluation order; lower runs first
         * @return this builder
         */
        public Builder priority(int priority) {
            this.priority = priority;
            return this;
        }

        /**
         * Builds the policy.
         *
         * @return the policy
         * @throws InvalidPolicyException if the policy is not valid or no decision was set
         */
        public Policy build() {
            Decision chosen = decision;
            if (chosen == null) {
                throw new InvalidPolicyException("Policy '" + id + "' has no decision");
            }
            return new Policy(id, tools, condition, chosen, priority);
        }
    }
}
