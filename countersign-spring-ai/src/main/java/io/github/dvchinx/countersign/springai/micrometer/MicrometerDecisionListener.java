package io.github.dvchinx.countersign.springai.micrometer;

import io.github.dvchinx.countersign.core.model.DecisionEvent;
import io.github.dvchinx.countersign.core.model.EvaluationResult;
import io.github.dvchinx.countersign.core.spi.DecisionListener;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Locale;
import java.util.Objects;

/**
 * Publishes Countersign's metrics to a Micrometer {@link MeterRegistry}.
 *
 * <ul>
 *   <li>{@value #DECISIONS} (counter): one per tool call, tagged {@code tool}, {@code decision}, {@code policy},
 *       {@code basis}, {@code outcome} ({@code proceed} or {@code refuse}) and {@code approval}. Alert on
 *       {@code outcome=refuse} to see what agents are being stopped from doing.
 *   <li>{@value #EVALUATION} (timer): time spent evaluating policies, tagged {@code tool}. Excludes waiting for a
 *       human, signing and auditing; calls whose arguments could not be read are not timed.
 *   <li>{@value #AUDIT_FAILURES} (counter): receipts that could not be created ({@code stage=receipt}) or stored
 *       ({@code stage=sink}), tagged {@code tool}. Without strict auditing a stored-receipt failure lets the call go
 *       on, so this counter is how it gets noticed.
 * </ul>
 *
 * <p>Tag values are bounded: tools are the registered ones, policies come from configuration, and everything else is
 * an enum. Values are lowercase with dashes, as in the YAML ({@code require-approval}); {@code none} stands for no
 * policy or no approval. Thread-safe.
 */
public final class MicrometerDecisionListener implements DecisionListener {

    /** The decisions counter. */
    public static final String DECISIONS = "countersign.decisions";

    /** The policy evaluation timer. */
    public static final String EVALUATION = "countersign.evaluation";

    /** The audit failures counter. */
    public static final String AUDIT_FAILURES = "countersign.audit.failures";

    private static final String NONE = "none";

    private final Meter.MeterProvider<Counter> decisions;
    private final Meter.MeterProvider<Timer> evaluation;
    private final Meter.MeterProvider<Counter> auditFailures;

    /**
     * Creates a listener that registers its meters on first use.
     *
     * @param registry where to publish
     */
    public MicrometerDecisionListener(MeterRegistry registry) {
        Objects.requireNonNull(registry, "registry");
        this.decisions = Counter.builder(DECISIONS)
                .description("Tool calls decided by Countersign")
                .withRegistry(registry);
        this.evaluation = Timer.builder(EVALUATION)
                .description("Time spent evaluating Countersign policies for one tool call")
                .withRegistry(registry);
        this.auditFailures = Counter.builder(AUDIT_FAILURES)
                .description("Countersign receipts that could not be created or stored")
                .withRegistry(registry);
    }

    @Override
    public void onDecision(DecisionEvent event) {
        decisions
                .withTags(
                        "tool",
                        event.tool(),
                        "decision",
                        tag(event.decision()),
                        "policy",
                        event.policyId() == null ? NONE : event.policyId(),
                        "basis",
                        tag(event.basis()),
                        "outcome",
                        event.proceeds() ? "proceed" : "refuse",
                        "approval",
                        event.approval() == null ? NONE : tag(event.approval()))
                .increment();
        if (event.basis() != EvaluationResult.Basis.INVALID_ARGUMENTS) {
            evaluation.withTags("tool", event.tool()).record(event.evaluationTime());
        }
        if (!event.receiptRecorded()) {
            auditFailures.withTags("tool", event.tool(), "stage", "receipt").increment();
        } else if (event.auditFailed()) {
            auditFailures.withTags("tool", event.tool(), "stage", "sink").increment();
        }
    }

    /** {@code REQUIRE_APPROVAL} becomes {@code require-approval}, as written in the configuration. */
    static String tag(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
