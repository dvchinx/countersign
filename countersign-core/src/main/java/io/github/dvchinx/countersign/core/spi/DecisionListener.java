package io.github.dvchinx.countersign.core.spi;

import io.github.dvchinx.countersign.core.model.DecisionEvent;
import io.github.dvchinx.countersign.core.model.Receipt;

/**
 * Hears about every decision, for metrics, tracing or alerting.
 *
 * <p>A port so the core can report what it did without depending on Micrometer or any other library. It is called
 * once per tool call, on the calling thread, after the receipt has been stored and before the tool runs. Keep it
 * fast: the tool waits for it.
 *
 * <p>A listener only observes. If it throws, Countersign logs the error and the decision stands: a broken metric never
 * lets a call through or blocks one.
 */
@FunctionalInterface
public interface DecisionListener {

    /**
     * Called once a tool call has been decided.
     *
     * @param event what was decided and how
     */
    void onDecision(DecisionEvent event);

    /**
     * Called when a tool ran but its result was withheld from the model because a redaction rule could not redact
     * it (ADR 0015). The tool's side effects already happened; the model gets a refusal instead of the result. The
     * receipt is not changed, so this call, the error log and the {@code countersign.audit.failures} metric are what
     * record the failure.
     *
     * <p>Does nothing by default, so a listener can still be written as a lambda.
     *
     * @param receipt the receipt recorded for the call before the tool ran
     * @param ruleId the redaction rule that failed
     */
    default void onResultWithheld(Receipt receipt, String ruleId) {}
}
