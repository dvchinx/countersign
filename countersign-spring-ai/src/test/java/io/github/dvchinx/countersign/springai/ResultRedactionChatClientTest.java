package io.github.dvchinx.countersign.springai;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dvchinx.countersign.core.approval.InMemoryApprovalGateway;
import io.github.dvchinx.countersign.core.guard.ToolCallGuard;
import io.github.dvchinx.countersign.core.model.ApprovalOutcome;
import io.github.dvchinx.countersign.core.model.Decision;
import io.github.dvchinx.countersign.core.model.Receipt;
import io.github.dvchinx.countersign.core.model.Redaction;
import io.github.dvchinx.countersign.core.policy.Policy;
import io.github.dvchinx.countersign.core.policy.PolicyEngine;
import io.github.dvchinx.countersign.core.receipt.ArgumentsHmac;
import io.github.dvchinx.countersign.core.receipt.Ed25519ReceiptSigner;
import io.github.dvchinx.countersign.core.receipt.ReceiptChain;
import io.github.dvchinx.countersign.core.receipt.ReceiptVerifier;
import io.github.dvchinx.countersign.core.redaction.FieldRedactor;
import io.github.dvchinx.countersign.core.redaction.PatternRedactor;
import io.github.dvchinx.countersign.core.redaction.RedactionRule;
import io.github.dvchinx.countersign.springai.micrometer.MicrometerDecisionListener;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.annotation.Tool;

/**
 * Result redaction through a real {@link ChatClient} tool call, with a scripted model: what the tool returned and
 * what the model saw on its next turn (ADR 0015).
 */
class ResultRedactionChatClientTest {

    /** A CRM tool as an app would write it: its result carries data the model should not see. */
    static class CrmTools {

        int lookups;

        record Customer(String name, String email, String phone, String card, int openTickets) {}

        @Tool(name = "crm_find_customer", description = "Find a customer by id")
        Customer findCustomer(String id) {
            lookups++;
            return new Customer("Ana Pérez", "ana@example.com", "+57 300 000 0000", "4111 1111 1111 1111", 2);
        }
    }

    private static final RedactionRule NO_CARDS = RedactionRule.builder("no-cards-to-the-model")
            .tools("crm_*")
            .target(Redaction.Target.RESULT)
            .redactors(
                    PatternRedactor.of("\\b\\d(?:[ -]?\\d){12,18}\\b", "[CARD]"),
                    FieldRedactor.of(List.of("email", "phone")))
            .build();

    private static final RedactionRule NO_AMOUNTS = RedactionRule.builder("no-amounts")
            .tools("payments.transfer")
            .target(Redaction.Target.RESULT)
            .redactors(PatternRedactor.of("\\d+", "[AMOUNT]"))
            .build();

    private static final PolicyEngine ENGINE = new PolicyEngine(
            Stream.concat(
                            BankPolicies.ENGINE.policies().stream(),
                            Stream.of(Policy.builder("crm-reads")
                                    .tools("crm_*")
                                    .decision(Decision.allow())
                                    .build()))
                    .toList(),
            Decision.deny("No policy allows this call"));

    private final CrmTools crm = new CrmTools();
    private final BankTools bank = new BankTools();
    private final List<Receipt> receipts = new ArrayList<>();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    private ToolCallGuard.Builder guard() {
        return ToolCallGuard.builder(
                        ENGINE,
                        ReceiptChain.start(
                                "crm-1",
                                new Ed25519ReceiptSigner(BankPolicies.KEYS.getPrivate()),
                                new ArgumentsHmac(new byte[32]),
                                Clock.systemUTC()),
                        receipts::add)
                .listener(new MicrometerDecisionListener(meters))
                .redaction(NO_CARDS)
                .redaction(NO_AMOUNTS);
    }

    private String ask(ScriptedChatModel model, ToolCallGuard guard) {
        return ChatClient.create(model)
                .prompt("do it")
                .tools((Object[]) GuardedToolCallback.guardAll(
                        guard,
                        Stream.of(ToolCallbacks.from(crm), ToolCallbacks.from(bank))
                                .flatMap(Stream::of)
                                .toArray(org.springframework.ai.tool.ToolCallback[]::new)))
                .toolContext(Map.of(GuardedToolCallback.PRINCIPAL_KEY, "alice"))
                .call()
                .content();
    }

    @Test
    void theModelNeverSeesTheCardOrTheContactDetails() {
        ScriptedChatModel model = new ScriptedChatModel("crm_find_customer", "{\"id\":\"c-7\"}");

        assertThat(ask(model, guard().build())).isEqualTo("done");

        assertThat(crm.lookups).isEqualTo(1);
        assertThat(model.toolResult())
                .isEqualTo("{\"name\":\"Ana Pérez\",\"email\":\"[REDACTED]\",\"phone\":\"[REDACTED]\","
                        + "\"card\":\"[CARD]\",\"openTickets\":2}");
        assertThat(receipts).singleElement().satisfies(receipt -> {
            assertThat(receipt.decision()).isEqualTo(Decision.Type.ALLOW);
            assertThat(receipt.matchedPolicies()).containsExactly("crm-reads");
            assertThat(receipt.redactions()).containsExactly("no-cards-to-the-model");
        });
        assertThat(meters.counter(
                                MicrometerDecisionListener.REDACTIONS,
                                "tool",
                                "crm_find_customer",
                                "rule",
                                "no-cards-to-the-model",
                                "target",
                                "result")
                        .count())
                .isEqualTo(1);
    }

    @Test
    void redactsTheResultOfATransferAHumanApproved() throws InterruptedException {
        InMemoryApprovalGateway approvals = new InMemoryApprovalGateway();
        ScriptedChatModel model = new ScriptedChatModel("payments.transfer", "{\"to\":\"ACME\",\"amount\":5000}");
        long giveUpAt = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        Thread approver = Thread.ofVirtual().start(() -> {
            while (!approvals.approve(firstPendingId(approvals), "bob") && System.nanoTime() < giveUpAt) {
                Thread.onSpinWait();
            }
        });

        ask(
                model,
                guard().approvals(approvals)
                        .approvalTimeout(Duration.ofSeconds(10))
                        .build());
        approver.join();

        assertThat(bank.transfers).isEqualTo(1);
        assertThat(model.toolResult()).isEqualTo("\"Transferred [AMOUNT] to ACME\"");
        assertThat(receipts).singleElement().satisfies(receipt -> {
            assertThat(receipt.approval()).isEqualTo(ApprovalOutcome.APPROVED);
            assertThat(receipt.redactions()).containsExactly("no-amounts");
        });
    }

    private static String firstPendingId(InMemoryApprovalGateway approvals) {
        return approvals.pending().isEmpty()
                ? "none-yet"
                : approvals.pending().getFirst().invocation().id();
    }

    @Test
    void leavesTheResultOfOtherToolsUntouched() {
        ScriptedChatModel model = new ScriptedChatModel("db.query", "{\"sql\":\"select 1\"}");

        ask(model, guard().build());

        String unguarded = Stream.of(ToolCallbacks.from(new BankTools()))
                .filter(tool -> tool.getToolDefinition().name().equals("db.query"))
                .findFirst()
                .orElseThrow()
                .call("{\"sql\":\"select 1\"}");
        assertThat(model.toolResult()).isEqualTo(unguarded);
        assertThat(receipts.getFirst().redactions()).isEmpty();
    }

    @Test
    void withholdsTheResultWhenARedactorFailsAfterTheToolRan() {
        RedactionRule failing = RedactionRule.builder("broken-detector")
                .tools("crm_*")
                .target(Redaction.Target.RESULT)
                .redactors(value -> {
                    throw new IllegalStateException("detector crashed");
                })
                .build();
        ScriptedChatModel model = new ScriptedChatModel("crm_find_customer", "{\"id\":\"c-7\"}");

        ask(model, guard().redaction(failing).build());

        assertThat(crm.lookups).as("the tool ran; only its result is withheld").isEqualTo(1);
        assertThat(model.toolResult())
                .contains("\"status\":\"WITHHELD\"", "\"tool\":\"crm_find_customer\"")
                .contains(receipts.getFirst().invocationId())
                .doesNotContain("Ana", "4111", "ana@example.com", "detector crashed");
        assertThat(meters.counter(
                                MicrometerDecisionListener.AUDIT_FAILURES,
                                "tool",
                                "crm_find_customer",
                                "stage",
                                "redaction")
                        .count())
                .isEqualTo(1);
    }

    @Test
    void everyRedactedCallLeavesAVerifiableReceipt() {
        ToolCallGuard guard = guard().build();
        ask(new ScriptedChatModel("crm_find_customer", "{\"id\":\"c-7\"}"), guard);
        ask(new ScriptedChatModel("db.query", "{\"sql\":\"DROP TABLE accounts\"}"), guard);
        ask(new ScriptedChatModel("payments.transfer", "{\"to\":\"ACME\",\"amount\":50}"), guard);

        assertThat(receipts)
                .extracting(Receipt::redactions)
                .containsExactly(List.of("no-cards-to-the-model"), List.of(), List.of("no-amounts"));
        assertThat(new ReceiptVerifier(BankPolicies.KEYS.getPublic())
                        .verify(receipts)
                        .isValid())
                .isTrue();
    }
}
