package io.github.dvchinx.countersign.core.spi;

import io.github.dvchinx.countersign.core.model.DecisionEvent;

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
}
