package io.github.dvchinx.countersign.compat.springai21;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dvchinx.countersign.core.guard.ToolCallGuard;
import io.github.dvchinx.countersign.core.model.Decision;
import io.github.dvchinx.countersign.core.model.Receipt;
import io.github.dvchinx.countersign.core.policy.Policy;
import io.github.dvchinx.countersign.core.policy.PolicyEngine;
import io.github.dvchinx.countersign.core.receipt.ArgumentsHmac;
import io.github.dvchinx.countersign.core.receipt.Ed25519ReceiptSigner;
import io.github.dvchinx.countersign.core.receipt.ReceiptChain;
import io.github.dvchinx.countersign.core.spi.AuditSink;
import io.github.dvchinx.countersign.springai.CountersignToolCallingBeanPostProcessor;
import io.github.dvchinx.countersign.springai.GuardedToolCallingManager;
import io.github.dvchinx.countersign.springai.spel.SpelCondition;
import java.math.BigDecimal;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.chat.client.autoconfigure.ChatClientAutoConfiguration;
import org.springframework.ai.model.tool.ToolCallDecision;
import org.springframework.ai.model.tool.ToolCallDecisions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring AI 2.1 tool call confirmation combined with Countersign (issue #10, ADR 0013).
 *
 * <p>Spring AI pauses a reply whose tool needs a person's confirmation; the application then resumes it with the
 * person's decisions through {@code ToolCallingManager.executeToolCalls(prompt, reply, decisions)}. These tests run
 * that flow against Spring AI's real auto-configuration, with the manager bean Countersign wraps, and check that the
 * resumed calls still go through the policies.
 */
class ToolCallConfirmationTest {

    private static final String QUESTION = "do it";

    private final BankTools bank = new BankTools();

    /** Tools as a Spring app would write them; every one needs a person's confirmation in Spring AI 2.1. */
    static final class BankTools {
        int transfers;
        int queries;

        @Tool(name = "payments_transfer", description = "Transfer money", requiresConfirmation = true)
        String transfer(String to, BigDecimal amount) {
            transfers++;
            return "Transferred " + amount + " to " + to;
        }

        @Tool(name = "db_query", description = "Run a SQL query", requiresConfirmation = true)
        String query(String sql) {
            queries++;
            return "3 rows";
        }
    }

    /** What the Countersign starter registers, written by hand to test the mechanism on its own. */
    @Configuration(proxyBeanMethods = false)
    static class CountersignConfiguration {

        @Bean
        static CountersignToolCallingBeanPostProcessor countersignToolCallingBeanPostProcessor(
                ObjectProvider<ToolCallGuard> guard) {
            return new CountersignToolCallingBeanPostProcessor(guard);
        }

        @Bean
        RecordingAuditSink auditSink() {
            return new RecordingAuditSink();
        }

        @Bean
        ToolCallGuard toolCallGuard(RecordingAuditSink auditSink) {
            return guard(auditSink);
        }
    }

    static final class RecordingAuditSink implements AuditSink {
        final List<Receipt> receipts = new CopyOnWriteArrayList<>();

        @Override
        public void append(Receipt receipt) {
            receipts.add(receipt);
        }
    }

    static ToolCallGuard guard(AuditSink sink) {
        PolicyEngine engine = new PolicyEngine(
                List.of(
                        Policy.builder("no-destructive-sql")
                                .tools("db_*")
                                .when(SpelCondition.parse(
                                        "#args['sql'] matches '(?i).*\\b(drop|truncate|delete)\\b.*'"))
                                .decision(Decision.deny("Destructive SQL is not allowed from agents"))
                                .build(),
                        Policy.builder("db-reads")
                                .tools("db_*")
                                .decision(Decision.allow())
                                .build(),
                        Policy.builder("transfers-need-approval")
                                .tools("payments_transfer")
                                .when(SpelCondition.parse("#args['amount'] > 1000"))
                                .decision(Decision.requireApproval("Moves real money"))
                                .build(),
                        Policy.builder("small-transfers")
                                .tools("payments_transfer")
                                .decision(Decision.allow())
                                .build()),
                Decision.deny("No policy allows this call"));
        try {
            ReceiptChain chain = ReceiptChain.start(
                    "bank-1",
                    new Ed25519ReceiptSigner(KeyPairGenerator.getInstance("Ed25519")
                            .generateKeyPair()
                            .getPrivate()),
                    new ArgumentsHmac(new byte[32]),
                    Clock.systemUTC());
            return ToolCallGuard.builder(engine, chain, sink).build();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private ApplicationContextRunner runner(ScriptedChatModel model) {
        return new ApplicationContextRunner()
                .withConfiguration(
                        AutoConfigurations.of(ToolCallingAutoConfiguration.class, ChatClientAutoConfiguration.class))
                .withUserConfiguration(CountersignConfiguration.class)
                .withBean(ChatModel.class, () -> model);
    }

    /** Asks through a {@code ChatClient}; Spring AI pauses the reply because the tool needs confirmation. */
    private ChatResponse pausedReply(AssertableApplicationContext context) {
        ChatResponse reply = context.getBean(ChatClient.Builder.class)
                .build()
                .prompt(QUESTION)
                .tools(bank)
                .call()
                .chatResponse();
        assertThat(ToolCallDecisions.awaitingDecision(reply)).containsExactly("call-1");
        return reply;
    }

    /** Resumes the paused reply with the person's decision, as Spring AI documents it. */
    private static ToolExecutionResult resume(
            ToolCallingManager manager, Object tools, ChatResponse reply, ToolCallDecision decision) {
        Prompt prompt = new Prompt(
                List.of(new UserMessage(QUESTION), reply.getResult().getOutput()),
                ToolCallingChatOptions.builder()
                        .toolCallbacks(ToolCallbacks.from(tools))
                        .build());
        return manager.executeToolCalls(prompt, reply, ToolCallDecisions.of(decision));
    }

    private static String toolResult(ToolExecutionResult result) {
        List<Message> history = result.conversationHistory();
        return ((ToolResponseMessage) history.getLast())
                .getResponses()
                .getFirst()
                .responseData();
    }

    @Test
    void wrapsTheManagerInOneThatCanResume() {
        runner(new ScriptedChatModel("db_query", "{}")).run(context -> {
            ToolCallingManager manager = context.getBean(ToolCallingManager.class);
            assertThat(manager).isInstanceOf(GuardedToolCallingManager.class);
            assertThat(manager.getClass().getSimpleName()).isEqualTo("ResumableGuardedToolCallingManager");
        });
    }

    @Test
    void stillPausesForConfirmationWhenTheToolsAreGuarded() {
        runner(new ScriptedChatModel("db_query", "{\"sql\":\"SELECT 1\"}")).run(context -> {
            pausedReply(context);

            assertThat(bank.queries).isZero();
            // Nothing ran yet, so Countersign has decided nothing yet.
            assertThat(context.getBean(RecordingAuditSink.class).receipts).isEmpty();
        });
    }

    @Test
    void deniesDestructiveSqlEvenWhenAPersonAllowedIt() {
        runner(new ScriptedChatModel("db_query", "{\"sql\":\"DROP TABLE accounts\"}"))
                .run(context -> {
                    ChatResponse reply = pausedReply(context);

                    ToolExecutionResult result = resume(
                            context.getBean(ToolCallingManager.class), bank, reply, ToolCallDecision.allow("call-1"));

                    assertThat(bank.queries).isZero();
                    assertThat(toolResult(result)).contains("REFUSED", "Destructive SQL is not allowed from agents");
                    assertThat(context.getBean(RecordingAuditSink.class).receipts)
                            .singleElement()
                            .extracting(Receipt::decision)
                            .isEqualTo(Decision.Type.DENY);
                });
    }

    @Test
    void runsAConfirmedCallThatThePoliciesAllow() {
        ScriptedChatModel model = new ScriptedChatModel("payments_transfer", "{\"to\":\"ACME\",\"amount\":50}");
        runner(model).run(context -> {
            ChatResponse reply = pausedReply(context);

            ToolExecutionResult result =
                    resume(context.getBean(ToolCallingManager.class), bank, reply, ToolCallDecision.allow("call-1"));

            assertThat(bank.transfers).isEqualTo(1);
            assertThat(toolResult(result)).contains("Transferred 50 to ACME");
            assertThat(context.getBean(RecordingAuditSink.class).receipts)
                    .singleElement()
                    .extracting(Receipt::decision)
                    .isEqualTo(Decision.Type.ALLOW);

            // The application sends the conversation back; the model reads the tool result and answers.
            String answer = context.getBean(ChatClient.Builder.class)
                    .build()
                    .prompt(new Prompt(result.conversationHistory()))
                    .call()
                    .content();
            assertThat(answer).isEqualTo("done");
            assertThat(model.lastToolResult()).contains("Transferred 50 to ACME");
        });
    }

    @Test
    void aPersonsConfirmationIsNotCountersignsApproval() {
        // Spring AI's confirmation does not say who confirmed; a require-approval policy still asks Countersign's
        // ApprovalGateway, and with none configured the call is refused.
        ScriptedChatModel model = new ScriptedChatModel("payments_transfer", "{\"to\":\"ACME\",\"amount\":5000}");
        runner(model).run(context -> {
            ChatResponse reply = pausedReply(context);

            ToolExecutionResult result =
                    resume(context.getBean(ToolCallingManager.class), bank, reply, ToolCallDecision.allow("call-1"));

            assertThat(bank.transfers).isZero();
            assertThat(toolResult(result)).contains("REFUSED", "Moves real money");
            assertThat(context.getBean(RecordingAuditSink.class).receipts)
                    .singleElement()
                    .extracting(Receipt::decision)
                    .isEqualTo(Decision.Type.REQUIRE_APPROVAL);
        });
    }

    @Test
    void aCallThePersonDeniedNeverRuns() {
        ScriptedChatModel model = new ScriptedChatModel("payments_transfer", "{\"to\":\"ACME\",\"amount\":50}");
        runner(model).run(context -> {
            ChatResponse reply = pausedReply(context);

            ToolExecutionResult result = resume(
                    context.getBean(ToolCallingManager.class), bank, reply, ToolCallDecision.deny("call-1", "No."));

            assertThat(bank.transfers).isZero();
            assertThat(toolResult(result)).isEqualTo("No.");
            // The tool never ran, so there was nothing for Countersign to decide.
            assertThat(context.getBean(RecordingAuditSink.class).receipts).isEmpty();
        });
    }

    @Test
    void failsClosedOnResumeWhenTheGuardIsMissing() {
        ScriptedChatModel model = new ScriptedChatModel("payments_transfer", "{\"to\":\"ACME\",\"amount\":50}");
        new ApplicationContextRunner()
                .withConfiguration(
                        AutoConfigurations.of(ToolCallingAutoConfiguration.class, ChatClientAutoConfiguration.class))
                .withBean(
                        CountersignToolCallingBeanPostProcessor.class,
                        () -> new CountersignToolCallingBeanPostProcessor(new EmptyGuardProvider()))
                .withBean(ChatModel.class, () -> model)
                .run(context -> {
                    // A ChatClient would already fail closed before pausing, when it guards the tools; take the
                    // paused reply from the model directly to reach the resume path.
                    ChatResponse reply = model.call(new Prompt(QUESTION));
                    ToolCallingManager manager = context.getBean(ToolCallingManager.class);

                    assertThatThrownBy(() -> resume(manager, bank, reply, ToolCallDecision.allow("call-1")))
                            .hasStackTraceContaining("ToolCallGuard");
                    assertThat(bank.transfers).isZero();
                });
    }

    @Test
    void aManagerGuardedByHandCanResume() {
        RecordingAuditSink sink = new RecordingAuditSink();
        ToolCallingManager manager =
                GuardedToolCallingManager.guard(ToolCallingManager.builder().build(), guard(sink));
        ScriptedChatModel model = new ScriptedChatModel("db_query", "{\"sql\":\"DROP TABLE accounts\"}");
        ChatResponse reply = model.call(new Prompt(QUESTION));

        ToolExecutionResult result = resume(manager, bank, reply, ToolCallDecision.allow("call-1"));

        assertThat(bank.queries).isZero();
        assertThat(toolResult(result)).contains("Destructive SQL is not allowed from agents");
        assertThat(sink.receipts).singleElement().extracting(Receipt::decision).isEqualTo(Decision.Type.DENY);
    }

    /** An {@link ObjectProvider} with no guard, as when Countersign is misconfigured. */
    private static final class EmptyGuardProvider implements ObjectProvider<ToolCallGuard> {
        @Override
        public ToolCallGuard getObject() {
            throw new NoSuchBeanDefinitionException(ToolCallGuard.class);
        }
    }
}
