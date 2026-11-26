package io.github.dvchinx.countersign.core.guard;

import io.github.dvchinx.countersign.core.model.CountersignException;
import java.io.Serial;

/**
 * A tool ran, but its result could not be redacted, so it must not reach the model (ADR 0015).
 *
 * <p>Thrown by {@link ResultRedaction#apply}. The cause is not attached: it may quote the data being redacted. The
 * guard has already logged the failure (its details at {@code DEBUG}) and told every {@code DecisionListener}.
 */
public final class ResultWithheldException extends CountersignException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final String ruleId;

    ResultWithheldException(String ruleId) {
        super("Redaction rule '" + ruleId + "' could not redact the result");
        this.ruleId = ruleId;
    }

    /**
     * Returns the rule that failed.
     *
     * @return the redaction rule id
     */
    public String ruleId() {
        return ruleId;
    }
}
