package io.github.dvchinx.countersign.core.model;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * A redaction rule that applies to a tool call that runs, and what it redacts.
 *
 * <p>Redaction is not a decision: it is an obligation on calls that run. Every rule that matches applies, whatever
 * decided access, so no approval or policy order can drop one (ADR 0015). Receipts record the rule ids; a
 * {@code DecisionListener} gets this type, so metrics can also tell what each rule redacts.
 *
 * @param ruleId the id of the redaction rule
 * @param targets what the rule redacts; at least one
 */
public record Redaction(String ruleId, Set<Target> targets) {

    /** What a redaction rule changes. */
    public enum Target {
        /** The arguments the tool receives. */
        ARGS,
        /** The result the model receives. */
        RESULT
    }

    /**
     * Creates a redaction.
     *
     * @throws IllegalArgumentException if the rule id is blank or no target is given
     */
    public Redaction {
        Objects.requireNonNull(ruleId, "ruleId");
        if (ruleId.isBlank()) {
            throw new IllegalArgumentException("ruleId must not be blank");
        }
        Objects.requireNonNull(targets, "targets");
        if (targets.isEmpty()) {
            throw new IllegalArgumentException("a redaction needs at least one target");
        }
        // An EnumSet iterates in declaration order, so metrics and logs list targets the same way every time.
        targets = Collections.unmodifiableSet(EnumSet.copyOf(targets));
    }
}
