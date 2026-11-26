package io.github.dvchinx.countersign.springai.micrometer;

import io.github.dvchinx.countersign.core.model.DecisionEvent;
import io.github.dvchinx.countersign.core.model.EvaluationResult;
import io.github.dvchinx.countersign.core.model.Receipt;
import io.github.dvchinx.countersign.core.model.Redaction;
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
 *       ({@code stage=sink}), and results withheld because they could not be redacted ({@code stage=redaction}),
 *       tagged {@code tool}. Without strict auditing a stored-receipt failure lets the call go on, so this counter is
 *       how it gets noticed.
 *   <li>{@value #REDACTIONS} (counter): one per redaction rule and target applied to a call that runs, tagged
 *       {@code tool}, {@code rule} and {@code target} ({@code result}). Counted when the call is decided, before the
 *       tool runs; it says that a rule applied, not that its redactors found something to change.
 * </ul>
 *
 * <p>Tag values are bounded: tools are the registered ones, policies and redaction rules come from configuration, and
 * everything else is an enum. Values are lowercase with dashes, as in the YAML ({@code require-approval}); {@code none} stands for no
 * policy or no approval. Thread-safe.
 */
public final class MicrometerDecisionListener implements DecisionListener {

    /** The decisions counter. */
    public static final String DECISIONS = "countersign.decisions";

    /** The policy evaluation timer. */
    public static final String EVALUATION = "countersign.evaluation";

    /** The audit failures counter. */
    public static final String AUDIT_FAILURES = "countersign.audit.failures";

    /** The redactions counter. */
    public static final String REDACTIONS = "countersign.redactions";

    private static final String NONE = "none";

    private final Meter.MeterProvider<Counter> decisions;
    private final Meter.MeterProvider<Timer> evaluation;
    private final Meter.MeterProvider<Counter> auditFailures;
    private final Meter.MeterProvider<Counter> redactions;

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
                .description("Countersign receipts that could not be created or stored, and results withheld")
                .withRegistry(registry);
        this.redactions = Counter.builder(REDACTIONS)
                .description("Countersign redaction rules applied to tool calls")
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
        for (Redaction redaction : event.redactions()) {
            for (Redaction.Target target : redaction.targets()) {
                redactions
                        .withTags("tool", event.tool(), "rule", redaction.ruleId(), "target", tag(target))
                        .increment();
            }
        }
    }

    @Override
    public void onResultWithheld(Receipt receipt, String ruleId) {
        auditFailures.withTags("tool", receipt.tool(), "stage", "redaction").increment();
    }

    /** {@code REQUIRE_APPROVAL} becomes {@code require-approval}, as written in the configuration. */
    static String tag(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
