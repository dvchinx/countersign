package io.github.dvchinx.countersign.springai;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dvchinx.countersign.core.guard.ToolCallGuard;
import io.github.dvchinx.countersign.core.model.Decision;
import io.github.dvchinx.countersign.core.model.EvaluationResult.Basis;
import io.github.dvchinx.countersign.core.model.Receipt;
import io.github.dvchinx.countersign.core.policy.Policy;
import io.github.dvchinx.countersign.core.policy.PolicyEngine;
import io.github.dvchinx.countersign.core.receipt.ArgumentsHmac;
import io.github.dvchinx.countersign.core.receipt.Ed25519ReceiptSigner;
import io.github.dvchinx.countersign.core.receipt.ReceiptChain;
import io.github.dvchinx.countersign.core.receipt.ReceiptVerifier;
import io.github.dvchinx.countersign.core.spi.ReceiptSigner;
import io.github.dvchinx.countersign.springai.spel.SpelCondition;
import java.math.BigDecimal;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.annotation.Tool;

/**
 * Drives real tool calls through a {@link ChatClient}, with a scripted model instead of a real API: the model asks
 * for one tool call, Spring AI's tool-calling loop runs it through {@link GuardedToolCallback}, and the model's
 * second turn shows what the tool returned.
 */
class GuardedToolCallbackChatClientTest {

    private static final KeyPair KEYS = keys();

    private final BankTools bank = new BankTools();
    private final List<Receipt> receipts = new ArrayList<>();

    private static final PolicyEngine POLICIES = new PolicyEngine(
            List.of(
                    Policy.builder("no-destructive-sql")
                            .tools("db.*")
                            .when(SpelCondition.parse("#args['sql'] matches '(?i).*\\b(drop|truncate|delete)\\b.*'"))
                            .decision(Decision.deny("Destructive SQL is not allowed from agents"))
                            .build(),
                    Policy.builder("db-reads")
                            .tools("db.*")
                            .decision(Decision.allow())
                            .build(),
                    Policy.builder("transfers-need-approval")
                            .tools("payments.transfer")
                            .when(SpelCondition.parse("#args['amount'] > 1000"))
                            .decision(Decision.requireApproval("Moves real money"))
                            .build(),
                    Policy.builder("small-transfers")
                            .tools("payments.transfer")
                            .decision(Decision.allow())
                            .build()),
            Decision.deny("No policy allows this call"));

    /** Tools as a Spring app would write them; they count how often they really ran. */
    static class BankTools {
        int transfers;
        int queries;

        @Tool(name = "payments.transfer", description = "Transfer money to another account")
        String transfer(String to, BigDecimal amount) {
            transfers++;
            return "Transferred " + amount + " to " + to;
        }

        @Tool(name = "db.query", description = "Run a SQL query")
        String query(String sql) {
            queries++;
            return "3 rows";
        }
    }

    /** A chat model that first requests one tool call, then answers; it keeps every prompt it receives. */
    static final class ScriptedChatModel implements ChatModel {
        private final Deque<AssistantMessage> script = new ArrayDeque<>();
        private final List<Prompt> prompts = new ArrayList<>();

        ScriptedChatModel(String toolName, String arguments) {
            script.add(AssistantMessage.builder()
                    .content("")
                    .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", toolName, arguments)))
                    .build());
            script.add(AssistantMessage.builder().content("done").build());
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            prompts.add(prompt);
            return new ChatResponse(List.of(new Generation(script.removeFirst())));
        }

        @Override
        public ChatOptions getOptions() {
            return ToolCallingChatOptions.builder().build();
        }

        /** What the tool call returned, as the model saw it on its second turn. */
        String toolResult() {
            List<Message> messages = prompts.getLast().getInstructions();
            return ((ToolResponseMessage) messages.getLast())
                    .getResponses()
                    .getFirst()
                    .responseData();
        }
    }

    private String ask(ScriptedChatModel model, ToolCallGuard guard) {
        return ChatClient.create(model)
                .prompt("do it")
                .tools((Object[]) GuardedToolCallback.guardAll(guard, ToolCallbacks.from(bank)))
                .toolContext(Map.of(GuardedToolCallback.PRINCIPAL_KEY, "alice"))
                .call()
                .content();
    }

    private ToolCallGuard guard(ReceiptSigner signer) {
        return new ToolCallGuard(
                POLICIES,
                ReceiptChain.start("bank-1", signer, new ArgumentsHmac(new byte[32]), Clock.systemUTC()),
                receipts::add,
                false);
    }

    private ToolCallGuard guard() {
        return guard(new Ed25519ReceiptSigner(KEYS.getPrivate()));
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
        assertThat(new ReceiptVerifier(KEYS.getPublic()).verify(receipts).isValid())
                .isTrue();
    }

    private static KeyPair keys() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
