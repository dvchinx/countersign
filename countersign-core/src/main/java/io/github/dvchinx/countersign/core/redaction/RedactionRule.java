package io.github.dvchinx.countersign.core.redaction;

import io.github.dvchinx.countersign.core.model.Redaction;
import io.github.dvchinx.countersign.core.model.ToolInvocation;
import io.github.dvchinx.countersign.core.policy.Condition;
import io.github.dvchinx.countersign.core.policy.InvalidPolicyException;
import io.github.dvchinx.countersign.core.policy.ToolPattern;
import io.github.dvchinx.countersign.core.spi.Redactor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * An obligation on tool calls that run: when the model calls one of {@link #tools()} and {@link #condition()} holds,
 * pass the {@link #targets()} through {@link #redactors()}, in order (ADR 0015).
 *
 * <p>Unlike a {@code Policy}, a rule does not decide whether a call runs, and rules are not first match wins:
 * <b>every</b> rule that matches a call that runs applies to it, whatever decided access. So an approved call still
 * gets its result redacted, and no {@code allow} listed earlier can drop a redaction.
 *
 * <p>A rule that targets {@link Redaction.Target#ARGS} changes what the tool receives, not what the policies, the
 * approver or the receipt's {@code argsHmac} see: they keep the arguments the model proposed. Its redactors must
 * return a JSON object. One that targets {@link Redaction.Target#RESULT} changes what the model gets back.
 *
 * @param id unique id, shared with policy ids and recorded in receipts; letters, digits, {@code .}, {@code _} and
 *     {@code -}
 * @param tools the tools this rule applies to; at least one
 * @param condition when the rule applies, once a tool pattern has matched; sees the arguments the model proposed
 * @param targets what the rule redacts
 * @param redactors the redactors to apply, in order; at least one
 */
public record RedactionRule(
        String id,
        List<ToolPattern> tools,
        Condition condition,
        Set<Redaction.Target> targets,
        List<Redactor> redactors) {

    private static final Pattern ID = Pattern.compile("[A-Za-z0-9._-]+");

    /**
     * Creates a rule.
     *
     * @throws InvalidPolicyException if the id is not valid, or no tool pattern, target or redactor is given
     */
    public RedactionRule {
        Objects.requireNonNull(id, "id");
        if (!ID.matcher(id).matches()) {
            throw new InvalidPolicyException("Redaction rule id must be non-empty and use only letters, digits, '.',"
                    + " '_' and '-': '" + id + "'");
        }
        tools = List.copyOf(tools);
        if (tools.isEmpty()) {
            throw new InvalidPolicyException("Redaction rule '" + id + "' must target at least one tool");
        }
        Objects.requireNonNull(condition, "condition");
        Objects.requireNonNull(targets, "targets");
        if (targets.isEmpty()) {
            throw new InvalidPolicyException("Redaction rule '" + id + "' needs a target (args, result or both)");
        }
        targets = Collections.unmodifiableSet(EnumSet.copyOf(targets));
        redactors = List.copyOf(redactors);
        if (redactors.isEmpty()) {
            throw new InvalidPolicyException("Redaction rule '" + id + "' needs at least one redactor");
        }
    }

    /**
     * Starts building a rule.
     *
     * @param id the rule id
     * @return a new builder
     */
    public static Builder builder(String id) {
        return new Builder(id);
    }

    /**
     * Tells whether this rule targets a tool, ignoring the condition.
     *
     * @param toolName the tool's name
     * @return {@code true} if any of the tool patterns matches
     */
    public boolean matchesTool(String toolName) {
        for (ToolPattern tool : tools) {
            if (tool.matches(toolName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Tells whether this rule applies to an invocation.
     *
     * @param invocation the invocation
     * @return {@code true} if a tool pattern matches and the condition holds
     * @throws RuntimeException whatever the condition throws; the caller must refuse the call (fail closed)
     */
    public boolean appliesTo(ToolInvocation invocation) {
        return matchesTool(invocation.toolName()) && condition.matches(invocation);
    }

    /**
     * Returns what this rule redacts, as reported to listeners.
     *
     * @return the redaction
     */
    public Redaction redaction() {
        return new Redaction(id, targets);
    }

    /** Builds a {@link RedactionRule}. The condition defaults to {@link Condition#always()}. Not thread-safe. */
    public static final class Builder {

        private final String id;
        private final List<ToolPattern> tools = new ArrayList<>();
        private Condition condition = Condition.always();
        private final Set<Redaction.Target> targets = EnumSet.noneOf(Redaction.Target.class);
        private final List<Redactor> redactors = new ArrayList<>();

        private Builder(String id) {
            this.id = id;
        }

        /**
         * Adds tool patterns.
         *
         * @param globs globs such as {@code crm_*}
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
         * @param condition when the rule applies
         * @return this builder
         */
        public Builder when(Condition condition) {
            this.condition = Objects.requireNonNull(condition, "condition");
            return this;
        }

        /**
         * Adds a target. Required.
         *
         * @param target what the rule redacts
         * @return this builder
         */
        public Builder target(Redaction.Target target) {
            targets.add(Objects.requireNonNull(target, "target"));
            return this;
        }

        /**
         * Adds redactors, applied in the order added. At least one is required.
         *
         * @param redactors the redactors
         * @return this builder
         */
        public Builder redactors(Redactor... redactors) {
            for (Redactor redactor : redactors) {
                this.redactors.add(Objects.requireNonNull(redactor, "redactor"));
            }
            return this;
        }

        /**
         * Builds the rule.
         *
         * @return the rule
         * @throws InvalidPolicyException if the rule is not valid
         */
        public RedactionRule build() {
            return new RedactionRule(id, tools, condition, targets, redactors);
        }
    }
}
