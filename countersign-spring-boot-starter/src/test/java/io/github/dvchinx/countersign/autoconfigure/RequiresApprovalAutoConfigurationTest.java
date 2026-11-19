package io.github.dvchinx.countersign.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dvchinx.countersign.autoconfigure.app.BankApplication;
import io.github.dvchinx.countersign.autoconfigure.app.BankTools;
import io.github.dvchinx.countersign.core.audit.InMemoryAuditSink;
import io.github.dvchinx.countersign.core.model.ApprovalDecision;
import io.github.dvchinx.countersign.core.model.ApprovalOutcome;
import io.github.dvchinx.countersign.core.model.Receipt;
import io.github.dvchinx.countersign.core.policy.InvalidPolicyException;
import io.github.dvchinx.countersign.core.spi.ApprovalGateway;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.chat.client.autoconfigure.ChatClientAutoConfiguration;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationContext;

/**
 * {@code @RequiresApproval} on a tool in the application's package, passed to a ChatClient as a plain object (never a
 * bean), with a configuration that would otherwise allow every call.
 */
@ExtendWith(OutputCaptureExtension.class)
class RequiresApprovalAutoConfigurationTest {

    private final BankTools tools = new BankTools();

    private ApplicationContextRunner runner(ScriptedChatModel model) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        ToolCallingAutoConfiguration.class,
                        ChatClientAutoConfiguration.class,
                        CountersignAutoConfiguration.class))
                .withUserConfiguration(BankApplication.class)
                .withPropertyValues(
                        "countersign.policies[0].id=allow-everything",
                        "countersign.policies[0].tools=*",
                        "countersign.policies[0].decision=allow")
                .withBean(ChatModel.class, () -> model);
    }

    private void ask(ApplicationContext context) {
        context.getBean(ChatClient.Builder.class)
                .build()
                .prompt("go")
                .tools(tools)
                .call()
                .content();
    }

    private static Receipt onlyReceipt(ApplicationContext context) {
        return context.getBean(InMemoryAuditSink.class).receipts().getFirst();
    }

    @Test
    void anAllowInConfigurationCannotSkipTheApprovalDeclaredInCode(CapturedOutput output) {
        ScriptedChatModel model = new ScriptedChatModel("payments.transfer", "{\"to\":\"ACME\",\"amount\":50}");

        runner(model).run(context -> {
            ask(context);

            assertThat(tools.transfers).isZero();
            assertThat(model.toolResult()).contains("REFUSED", "Moves real money");
            assertThat(onlyReceipt(context).matchedPolicies()).containsExactly("requires-approval.payments.transfer");
            assertThat(onlyReceipt(context).approval()).isEqualTo(ApprovalOutcome.UNAVAILABLE);
            assertThat(output).contains("2 policies (1 from @RequiresApproval)");
        });
    }

    @Test
    void runsTheAnnotatedToolOnceApproved() {
        ScriptedChatModel model = new ScriptedChatModel("payments.transfer", "{\"to\":\"ACME\",\"amount\":50}");

        runner(model)
                .withBean(
                        ApprovalGateway.class,
                        () -> request -> CompletableFuture.completedFuture(ApprovalDecision.approved("bob")))
                .run(context -> {
                    ask(context);

                    assertThat(tools.transfers).isEqualTo(1);
                    assertThat(onlyReceipt(context).approver()).isEqualTo("bob");
                });
    }

    @Test
    void leavesToolsWithoutTheAnnotationAlone() {
        ScriptedChatModel model = new ScriptedChatModel("payments.balance", "{\"account\":\"A-1\"}");

        runner(model).run(context -> {
            ask(context);

            assertThat(tools.balances).isEqualTo(1);
            assertThat(onlyReceipt(context).matchedPolicies()).containsExactly("allow-everything");
        });
    }

    @Test
    void refusesToStartWhenTheAnnotationIsOnAMethodThatIsNotATool() {
        runner(new ScriptedChatModel("x", "{}"))
                .withPropertyValues(
                        "countersign.annotation-packages=io.github.dvchinx.countersign.autoconfigure.misplaced")
                .run(context -> assertThat(context.getStartupFailure())
                        .rootCause()
                        .isInstanceOf(InvalidPolicyException.class)
                        .hasMessageContaining("NotATool.transfer()"));
    }
}
