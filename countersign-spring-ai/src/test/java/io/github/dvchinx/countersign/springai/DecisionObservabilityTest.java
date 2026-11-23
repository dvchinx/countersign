package io.github.dvchinx.countersign.springai;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dvchinx.countersign.core.guard.ToolCallGuard;
import io.github.dvchinx.countersign.core.model.JsonObject;
import io.github.dvchinx.countersign.core.model.Receipt;
import io.github.dvchinx.countersign.core.model.ToolInvocation;
import io.github.dvchinx.countersign.core.receipt.Ed25519ReceiptSigner;
import io.github.dvchinx.countersign.core.spi.AuditSink;
import io.github.dvchinx.countersign.core.spi.ReceiptSigner;
import io.github.dvchinx.countersign.springai.micrometer.MicrometerDecisionListener;
import io.github.dvchinx.countersign.springai.micrometer.ObservationDecisionListener;
import io.micrometer.common.KeyValue;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.chat.client.autoconfigure.ChatClientAutoConfiguration;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.ai.tool.observation.ToolCallingObservationContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Metrics and tracing for guarded tool calls: real calls through a {@link ChatClient} and Spring AI's tool-calling
 * auto-configuration, observed with Micrometer.
 */
class DecisionObservabilityTest {

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final List<Observation.Context> stopped = new CopyOnWriteArrayList<>();
    private final ObservationRegistry observations = recordingRegistry(stopped);
    private final BankTools bank = new BankTools();
    private final List<Receipt> receipts = new CopyOnWriteArrayList<>();

    private static ObservationRegistry recordingRegistry(List<Observation.Context> stopped) {
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new ObservationHandler<>() {
            @Override
            public boolean supportsContext(Observation.Context context) {
                return true;
            }

            @Override
            public void onStop(Observation.Context context) {
                stopped.add(context);
            }
        });
        return registry;
    }

    private ToolCallGuard guard(ReceiptSigner signer, AuditSink sink) {
        return BankPolicies.builder(signer, sink)
                .listener(new MicrometerDecisionListener(meters))
                .listener(new ObservationDecisionListener(observations))
                .build();
    }

    private ToolCallGuard guard() {
        return guard(new Ed25519ReceiptSigner(BankPolicies.KEYS.getPrivate()), receipts::add);
    }

    /** What the starter registers, written by hand here. */
    @Configuration(proxyBeanMethods = false)
    static class Countersign {

        @Bean
        static CountersignToolCallingBeanPostProcessor countersignToolCallingBeanPostProcessor(
                ObjectProvider<ToolCallGuard> guard) {
            return new CountersignToolCallingBeanPostProcessor(guard);
        }
    }

    private void ask(String toolName, String arguments) {
        new ApplicationContextRunner()
                .withConfiguration(
                        AutoConfigurations.of(ToolCallingAutoConfiguration.class, ChatClientAutoConfiguration.class))
                .withUserConfiguration(Countersign.class)
                .withBean(ToolCallGuard.class, this::guard)
                .withBean(ObservationRegistry.class, () -> observations)
                .withBean(ChatModel.class, () -> new ScriptedChatModel(toolName, arguments))
                .run(context -> context.getBean(ChatClient.Builder.class)
                        .build()
                        .prompt("do it")
                        .tools(bank)
                        .call()
                        .content());
    }

    private double decisions(String... tags) {
        return meters.counter(MicrometerDecisionListener.DECISIONS, tags).count();
    }

    private ToolCallingObservationContext toolSpan() {
        return stopped.stream()
                .filter(ToolCallingObservationContext.class::isInstance)
                .map(ToolCallingObservationContext.class::cast)
                .reduce((first, second) -> second)
                .orElseThrow(() -> new AssertionError("No spring.ai.tool observation was stopped"));
    }

    private static String value(Observation.Context context, String key) {
        KeyValue keyValue = context.getHighCardinalityKeyValue(key);
        return keyValue == null ? "" : keyValue.getValue();
    }

    @Test
    void countsAnAllowedCallAndTimesItsEvaluation() {
        ask("payments.transfer", "{\"to\":\"ACME\",\"amount\":50}");

        assertThat(bank.transfers).isEqualTo(1);
        assertThat(decisions(
                        "tool", "payments.transfer",
                        "decision", "allow",
                        "policy", "small-transfers",
                        "basis", "policy-matched",
                        "outcome", "proceed",
                        "approval", "none"))
                .isEqualTo(1);
        assertThat(meters.timer(MicrometerDecisionListener.EVALUATION, "tool", "payments.transfer")
                        .count())
                .isEqualTo(1);
    }

    @Test
    void countsACallRefusedForWantOfAnApprover() {
        ask("payments.transfer", "{\"to\":\"ACME\",\"amount\":5000}");

        assertThat(bank.transfers).isZero();
        assertThat(decisions(
                        "tool", "payments.transfer",
                        "decision", "require-approval",
                        "policy", "transfers-need-approval",
                        "basis", "policy-matched",
                        "outcome", "refuse",
                        "approval", "unavailable"))
                .isEqualTo(1);
    }

    @Test
    void marksTheToolCallSpanWithTheDecisionAndItsReceipt() {
        ask("db.query", "{\"sql\":\"DROP TABLE accounts\"}");

        Receipt receipt = receipts.getFirst();
        ToolCallingObservationContext span = toolSpan();
        assertThat(value(span, "countersign.decision")).isEqualTo("deny");
        assertThat(value(span, "countersign.outcome")).isEqualTo("refuse");
        assertThat(value(span, "countersign.policy")).isEqualTo("no-destructive-sql");
        assertThat(value(span, "countersign.receipt")).isEqualTo(receipt.streamId() + "#" + receipt.sequence());
        assertThat(span.getLowCardinalityKeyValues().stream().map(KeyValue::getKey))
                .noneMatch(key -> key.startsWith("countersign."));
    }

    @Test
    void countsAReceiptThatCouldNotBeStored() {
        AuditSink down = receipt -> {
            throw new IllegalStateException("database down");
        };

        guard(new Ed25519ReceiptSigner(BankPolicies.KEYS.getPrivate()), down).check(dbRead());

        assertThat(meters.counter(MicrometerDecisionListener.AUDIT_FAILURES, "tool", "db.query", "stage", "sink")
                        .count())
                .isEqualTo(1);
    }

    @Test
    void countsAReceiptThatCouldNotBeSigned() {
        ReceiptSigner broken = hash -> {
            throw new IllegalStateException("HSM down");
        };

        guard(broken, receipts::add).check(dbRead());

        assertThat(meters.counter(MicrometerDecisionListener.AUDIT_FAILURES, "tool", "db.query", "stage", "receipt")
                        .count())
                .isEqualTo(1);
        assertThat(decisions(
                        "tool", "db.query",
                        "decision", "allow",
                        "policy", "db-reads",
                        "basis", "policy-matched",
                        "outcome", "refuse",
                        "approval", "none"))
                .isEqualTo(1);
    }

    @Test
    void countsUnreadableArgumentsWithoutTimingThem() {
        guard().refuseInvalidArguments("db.query", "inv-1", null, null, Instant.now());

        assertThat(decisions(
                        "tool", "db.query",
                        "decision", "deny",
                        "policy", "none",
                        "basis", "invalid-arguments",
                        "outcome", "refuse",
                        "approval", "none"))
                .isEqualTo(1);
        assertThat(meters.find(MicrometerDecisionListener.EVALUATION).timers()).isEmpty();
    }

    @Test
    void leavesOtherObservationsAlone() {
        Observation.createNotStarted("http.server.requests", observations).observe(() -> guard().check(dbRead()));

        assertThat(stopped)
                .singleElement()
                .satisfies(context ->
                        assertThat(context.getHighCardinalityKeyValues()).isEmpty());
    }

    private static ToolInvocation dbRead() {
        return ToolInvocation.of(
                "db.query", JsonObject.builder().put("sql", "SELECT 1").build());
    }
}
