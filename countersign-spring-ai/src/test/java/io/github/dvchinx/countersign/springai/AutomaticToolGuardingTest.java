package io.github.dvchinx.countersign.springai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dvchinx.countersign.core.guard.ToolCallGuard;
import io.github.dvchinx.countersign.core.model.Decision;
import io.github.dvchinx.countersign.core.model.Receipt;
import io.github.dvchinx.countersign.core.spi.AuditSink;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.chat.client.autoconfigure.ChatClientAutoConfiguration;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.ai.tool.resolution.ToolCallbackResolver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Proves the automatic wrapping against Spring AI's own auto-configuration: the application registers its tools the
 * usual way and writes no Countersign code around them, yet every call is checked.
 */
class AutomaticToolGuardingTest {

    private final BankTools bank = new BankTools();

    /** What the Countersign starter will register; written by hand here to test the mechanism on its own. */
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
            return BankPolicies.guard(auditSink);
        }
    }

    static final class RecordingAuditSink implements AuditSink {
        final List<Receipt> receipts = new CopyOnWriteArrayList<>();

        @Override
        public void append(Receipt receipt) {
            receipts.add(receipt);
        }
    }

    private ApplicationContextRunner runner(ScriptedChatModel model) {
        return new ApplicationContextRunner()
                .withConfiguration(
                        AutoConfigurations.of(ToolCallingAutoConfiguration.class, ChatClientAutoConfiguration.class))
                .withUserConfiguration(CountersignConfiguration.class)
                .withBean(ChatModel.class, () -> model);
    }

    @Test
    void wrapsSpringAisToolCallingBeans() {
        runner(new ScriptedChatModel("db.query", "{}")).run(context -> {
            assertThat(context.getBean(ToolCallingManager.class)).isInstanceOf(GuardedToolCallingManager.class);
            assertThat(context.getBean(ToolCallbackResolver.class)).isInstanceOf(GuardedToolCallbackResolver.class);
        });
    }

    @Test
    void deniesTransferAboveLimitPassedAsAPlainToolsObject() {
        ScriptedChatModel model = new ScriptedChatModel("payments.transfer", "{\"to\":\"ACME\",\"amount\":5000}");

        runner(model).run(context -> {
            String answer = context.getBean(ChatClient.Builder.class)
                    .build()
                    .prompt("pay ACME 5000")
                    .tools(bank)
                    .call()
                    .content();

            assertThat(answer).isEqualTo("done");
            assertThat(bank.transfers).isZero();
            assertThat(model.toolResult()).contains("REFUSED", "Moves real money");
            assertThat(context.getBean(RecordingAuditSink.class).receipts)
                    .singleElement()
                    .extracting(Receipt::decision)
                    .isEqualTo(Decision.Type.REQUIRE_APPROVAL);
        });
    }

    @Test
    void runsAllowedToolsPassedAsAPlainToolsObject() {
        ScriptedChatModel model = new ScriptedChatModel("payments.transfer", "{\"to\":\"ACME\",\"amount\":50}");

        runner(model).run(context -> {
            context.getBean(ChatClient.Builder.class)
                    .build()
                    .prompt("pay ACME 50")
                    .tools(bank)
                    .call()
                    .content();

            assertThat(bank.transfers).isEqualTo(1);
            assertThat(model.toolResult()).contains("Transferred 50 to ACME");
        });
    }

    @Test
    void deniesDestructiveSqlFromDefaultTools() {
        ScriptedChatModel model = new ScriptedChatModel("db.query", "{\"sql\":\"DROP TABLE accounts\"}");

        runner(model).run(context -> {
            ChatClient client =
                    context.getBean(ChatClient.Builder.class).defaultTools(bank).build();

            client.prompt("clean up").call().content();

            assertThat(bank.queries).isZero();
            assertThat(model.toolResult()).contains("Destructive SQL is not allowed from agents");
        });
    }

    @Test
    void guardsToolsResolvedByNameEvenIfTheyWereNeverOffered() {
        // The model calls a tool that is not in the request; Spring AI's resolution fallback finds it by name.
        ScriptedChatModel model = new ScriptedChatModel("db.query", "{\"sql\":\"DROP TABLE accounts\"}");

        runner(model)
                .withPropertyValues("spring.ai.tools.resolution.fallback.enabled=true")
                .withBean(
                        ToolCallbackProvider.class,
                        () -> MethodToolCallbackProvider.builder()
                                .toolObjects(bank)
                                .build())
                .run(context -> {
                    context.getBean(ChatClient.Builder.class)
                            .build()
                            .prompt("clean up")
                            .call()
                            .content();

                    assertThat(bank.queries).isZero();
                    assertThat(model.toolResult()).contains("REFUSED");
                    assertThat(context.getBean(RecordingAuditSink.class).receipts)
                            .hasSize(1);
                });
    }

    @Test
    void failsClosedWhenTheGuardIsMissing() {
        ScriptedChatModel model = new ScriptedChatModel("payments.transfer", "{\"to\":\"ACME\",\"amount\":50}");

        new ApplicationContextRunner()
                .withConfiguration(
                        AutoConfigurations.of(ToolCallingAutoConfiguration.class, ChatClientAutoConfiguration.class))
                .withBean(
                        CountersignToolCallingBeanPostProcessor.class,
                        () -> new CountersignToolCallingBeanPostProcessor(new EmptyGuardProvider()))
                .withBean(ChatModel.class, () -> model)
                .run(context -> {
                    ChatClient client =
                            context.getBean(ChatClient.Builder.class).build();

                    assertThatThrownBy(() ->
                                    client.prompt("pay").tools(bank).call().content())
                            .hasStackTraceContaining("ToolCallGuard");
                    assertThat(bank.transfers).isZero();
                });
    }

    /** An {@link ObjectProvider} with no guard, as when Countersign is misconfigured. */
    private static final class EmptyGuardProvider implements ObjectProvider<ToolCallGuard> {
        @Override
        public ToolCallGuard getObject() {
            throw new org.springframework.beans.factory.NoSuchBeanDefinitionException(ToolCallGuard.class);
        }
    }
}
