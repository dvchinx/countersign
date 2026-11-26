package io.github.dvchinx.countersign.core.guard;

import io.github.dvchinx.countersign.core.model.JsonValue;
import io.github.dvchinx.countersign.core.redaction.RedactionRule;
import io.github.dvchinx.countersign.core.spi.Redactor;
import java.util.List;
import java.util.Objects;

/**
 * The redaction a tool's result must go through before the model sees it: the redactors of every rule that targets
 * the result of this call, in order (ADR 0015).
 *
 * <p>Handed to adapters in {@link Verdict.Proceed}, because the result only exists once the tool has run. An adapter
 * passes the result through {@link #apply} and returns what comes back; if {@link #apply} throws, it must withhold
 * the result and return a refusal instead. The failure has already been logged and reported to every
 * {@code DecisionListener} by then.
 *
 * <p>Immutable and thread-safe.
 */
public final class ResultRedaction {

    private static final ResultRedaction NONE = new ResultRedaction(List.of(), (ruleId, failure) -> {});

    /** What the guard does when a rule fails: log it and tell the listeners. Must not throw. */
    @FunctionalInterface
    interface FailureHandler {
        void failed(String ruleId, RuntimeException failure);
    }

    private final List<RedactionRule> rules;
    private final FailureHandler onFailure;

    ResultRedaction(List<RedactionRule> rules, FailureHandler onFailure) {
        this.rules = List.copyOf(rules);
        this.onFailure = Objects.requireNonNull(onFailure, "onFailure");
    }

    /**
     * Returns the redaction that leaves a result as it is.
     *
     * @return the empty redaction
     */
    public static ResultRedaction none() {
        return NONE;
    }

    /**
     * Tells whether there is nothing to redact, so an adapter can skip parsing the result.
     *
     * @return {@code true} if no rule targets the result of this call
     */
    public boolean isEmpty() {
        return rules.isEmpty();
    }

    /**
     * Returns the ids of the rules applied to the result, in order.
     *
     * @return the rule ids
     */
    public List<String> ruleIds() {
        return rules.stream().map(RedactionRule::id).toList();
    }

    /**
     * Redacts a tool's result.
     *
     * @param result the result, as a JSON tree; text that is not JSON is passed as one string value
     * @return the redacted result
     * @throws ResultWithheldException if a redactor throws or returns {@code null}; the result must not reach the model
     */
    public JsonValue apply(JsonValue result) {
        Objects.requireNonNull(result, "result");
        JsonValue value = result;
        for (RedactionRule rule : rules) {
            try {
                for (Redactor redactor : rule.redactors()) {
                    value = Objects.requireNonNull(redactor.redact(value), "a redactor returned null");
                }
            } catch (RuntimeException e) {
                onFailure.failed(rule.id(), e);
                throw new ResultWithheldException(rule.id());
            }
        }
        return value;
    }

    @Override
    public String toString() {
        return "ResultRedaction" + ruleIds();
    }
}
