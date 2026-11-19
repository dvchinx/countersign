package io.github.dvchinx.countersign.springai;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dvchinx.countersign.core.approval.InMemoryApprovalGateway;
import io.github.dvchinx.countersign.core.guard.ToolCallGuard;
import io.github.dvchinx.countersign.core.model.ApprovalOutcome;
import io.github.dvchinx.countersign.core.model.Decision;
import io.github.dvchinx.countersign.core.model.EvaluationResult.Basis;
import io.github.dvchinx.countersign.core.model.Receipt;
import io.github.dvchinx.countersign.core.receipt.ReceiptVerifier;
import io.github.dvchinx.countersign.core.spi.ReceiptSigner;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.support.ToolCallbacks;

/**
 * Drives real tool calls through a {@link ChatClient}, with a scripted model instead of a real API: the model asks
 * for one tool call, Spring AI's tool-calling loop runs it through {@link GuardedToolCallback}, and the model's
 * second turn shows what the tool returned.
 */
class GuardedToolCallbackChatClientTest {

    private final BankTools bank = new BankTools();
    private final List<Receipt> receipts = new ArrayList<>();

    private ToolCallGuard guard(ReceiptSigner signer) {
        return BankPolicies.guard(signer, receipts::add);
    }

    private ToolCallGuard guard() {
        return BankPolicies.guard(receipts::add);
    }

    private String ask(ScriptedChatModel model, ToolCallGuard guard) {
        return ChatClient.create(model)
                .prompt("do it")
                .tools((Object[]) GuardedToolCallback.guardAll(guard, ToolCallbacks.from(bank)))
                .toolContext(Map.of(GuardedToolCallback.PRINCIPAL_KEY, "alice"))
                .call()
                .content();
    }

    @Test
    void runsSmallTransferAndRecordsWhoAskedForIt() {
        ScriptedChatModel model = new ScriptedChatModel("payments.transfer", "{\"to\":\"ACME\",\"amount\":50}");

        assertThat(ask(model, guard())).isEqualTo("done");

        assertThat(bank.transfers).isEqualTo(1);
        assertThat(model.toolResult()).isEqualTo("\"Transferred 50 to ACME\"");
        assertThat(receipts).singleElement().satisfies(receipt -> {
            assertThat(receipt.decision()).isEqualTo(Decision.Type.ALLOW);
            assertThat(receipt.matchedPolicies()).containsExactly("small-transfers");
            assertThat(receipt.principal()).isEqualTo("alice");
        });
    }

    @Test
    void deniesTransferAboveLimitWhenNoApproval() {
        ScriptedChatModel model = new ScriptedChatModel("payments.transfer", "{\"to\":\"ACME\",\"amount\":5000}");

        ask(model, guard());

        assertThat(bank.transfers).isZero();
        assertThat(model.toolResult())
                .contains("\"status\":\"REFUSED\"", "\"decision\":\"REQUIRE_APPROVAL\"", "Moves real money");
        assertThat(receipts.getFirst().matchedPolicies()).containsExactly("transfers-need-approval");
    }

    @Test
    void runsTransferAboveLimitOnceAHumanApprovesIt() throws InterruptedException {
        InMemoryApprovalGateway approvals = new InMemoryApprovalGateway();
        ScriptedChatModel model = new ScriptedChatModel("payments.transfer", "{\"to\":\"ACME\",\"amount\":5000}");
        // A human approves from another thread while the agent's tool call waits, as an approval screen would.
        long giveUpAt = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        Thread approver = Thread.ofVirtual().start(() -> {
            while (!approvals.approve(firstPendingId(approvals), "bob") && System.nanoTime() < giveUpAt) {
                Thread.onSpinWait();
            }
        });

        ask(model, BankPolicies.guard(receipts::add, approvals, Duration.ofSeconds(10)));
        approver.join();

        assertThat(bank.transfers).isEqualTo(1);
        assertThat(model.toolResult()).isEqualTo("\"Transferred 5000 to ACME\"");
        assertThat(receipts).singleElement().satisfies(receipt -> {
            assertThat(receipt.decision()).isEqualTo(Decision.Type.REQUIRE_APPROVAL);
            assertThat(receipt.approval()).isEqualTo(ApprovalOutcome.APPROVED);
            assertThat(receipt.approver()).isEqualTo("bob");
            assertThat(receipt.principal()).isEqualTo("alice");
        });
    }

    @Test
    void deniesTransferAboveLimitWhenNobodyApprovesInTime() {
        InMemoryApprovalGateway approvals = new InMemoryApprovalGateway();
        ScriptedChatModel model = new ScriptedChatModel("payments.transfer", "{\"to\":\"ACME\",\"amount\":5000}");

        ask(model, BankPolicies.guard(receipts::add, approvals, Duration.ofMillis(100)));

        assertThat(bank.transfers).isZero();
        assertThat(model.toolResult()).contains("REFUSED", "no answer arrived in time");
        assertThat(receipts.getFirst().approval()).isEqualTo(ApprovalOutcome.TIMED_OUT);
        assertThat(approvals.pending()).isEmpty();
    }

    private static String firstPendingId(InMemoryApprovalGateway approvals) {
        return approvals.pending().isEmpty()
                ? "none-yet"
                : approvals.pending().getFirst().invocation().id();
    }

    @Test
    void deniesDestructiveSqlWithAStructuredRefusal() {
        ScriptedChatModel model = new ScriptedChatModel("db.query", "{\"sql\":\"DROP TABLE accounts\"}");

        ask(model, guard());

        assertThat(bank.queries).isZero();
        assertThat(model.toolResult())
                .contains("\"decision\":\"DENY\"", "Destructive SQL is not allowed from agents")
                .doesNotContain("DROP", "Exception");
    }

    @Test
    void deniesDuplicateArgumentsThatCouldHideTheRealAmount() {
        // A lenient parser keeps the last value, so the policy could see 50 while the tool sees 5000, or the reverse.
        ScriptedChatModel model =
                new ScriptedChatModel("payments.transfer", "{\"to\":\"ACME\",\"amount\":50,\"amount\":5000}");

        ask(model, guard());

        assertThat(bank.transfers).isZero();
        assertThat(model.toolResult()).contains("REFUSED");
        assertThat(receipts.getFirst().basis()).isEqualTo(Basis.INVALID_ARGUMENTS);
    }

    @Test
    void deniesTransferWhenTheReceiptCannotBeSigned() {
        ScriptedChatModel model = new ScriptedChatModel("payments.transfer", "{\"to\":\"ACME\",\"amount\":50}");

        ask(model, guard(hash -> {
            throw new IllegalStateException("HSM unreachable");
        }));

        assertThat(bank.transfers).isZero();
        assertThat(model.toolResult()).contains("REFUSED").doesNotContain("HSM");
        assertThat(receipts).isEmpty();
    }

    @Test
    void everyCallLeavesAVerifiableReceipt() {
        ToolCallGuard guard = guard();
        ask(new ScriptedChatModel("payments.transfer", "{\"to\":\"ACME\",\"amount\":50}"), guard);
        ask(new ScriptedChatModel("payments.transfer", "{\"to\":\"ACME\",\"amount\":5000}"), guard);
        ask(new ScriptedChatModel("db.query", "{\"sql\":\"select 1\"}"), guard);
        ask(new ScriptedChatModel("db.query", "not json"), guard);

        assertThat(receipts)
                .extracting(Receipt::decision)
                .containsExactly(
                        Decision.Type.ALLOW, Decision.Type.REQUIRE_APPROVAL, Decision.Type.ALLOW, Decision.Type.DENY);
        assertThat(new ReceiptVerifier(BankPolicies.KEYS.getPublic())
                        .verify(receipts)
                        .isValid())
                .isTrue();
    }
}
