package io.github.dvchinx.countersign.springai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.github.dvchinx.countersign.core.guard.ToolCallGuard;
import io.github.dvchinx.countersign.core.model.Receipt;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.support.ToolCallbacks;

class GuardedToolCallingManagerTest {

    private final BankTools bank = new BankTools();
    private final List<Receipt> receipts = new ArrayList<>();
    private final ToolCallGuard guard = BankPolicies.guard(receipts::add);

    private static ChatResponse modelCalls(String tool, String arguments) {
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", tool, arguments)))
                .build())));
    }

    private Prompt promptWithBankTools() {
        return new Prompt(
                List.of(new UserMessage("pay")),
                ToolCallingChatOptions.builder()
                        .toolCallbacks(List.of(ToolCallbacks.from(bank)))
                        .build());
    }

    private static String toolResult(ToolExecutionResult result) {
        return ((ToolResponseMessage) result.conversationHistory().getLast())
                .getResponses()
                .getFirst()
                .responseData();
    }

    @Test
    void guardsUnwrappedToolsFromTheRequestOptions() {
        ToolCallingManager manager =
                GuardedToolCallingManager.guard(ToolCallingManager.builder().build(), guard);

        ToolExecutionResult result = manager.executeToolCalls(
                promptWithBankTools(), modelCalls("payments.transfer", "{\"to\":\"ACME\",\"amount\":5000}"));

        assertThat(bank.transfers).isZero();
        assertThat(toolResult(result)).contains("REFUSED", "REQUIRE_APPROVAL");
        assertThat(receipts).hasSize(1);
    }

    @Test
    void stillRunsAllowedTools() {
        ToolCallingManager manager =
                GuardedToolCallingManager.guard(ToolCallingManager.builder().build(), guard);

        ToolExecutionResult result = manager.executeToolCalls(
                promptWithBankTools(), modelCalls("payments.transfer", "{\"to\":\"ACME\",\"amount\":50}"));

        assertThat(bank.transfers).isEqualTo(1);
        assertThat(toolResult(result)).contains("Transferred 50 to ACME");
    }

    @Test
    void loadsTheSpringAi21VariantOnlyWhenSpringAiCanResumeToolCalls() {
        // The default build runs on the Spring AI 2.0 baseline, where the variant compiled against 2.1 must never be
        // used; CI also runs this suite on 2.1, where it must be (compat/spring-ai-2.1 tests its behavior).
        boolean springAi21 = hasClass("org.springframework.ai.model.tool.ToolCallDecisions");

        ToolCallingManager manager =
                GuardedToolCallingManager.guard(ToolCallingManager.builder().build(), guard);

        assertThat(GuardedToolCallingManager.supportsToolCallDecisions()).isEqualTo(springAi21);
        assertThat(manager.getClass().getName())
                .isEqualTo(
                        springAi21
                                ? "io.github.dvchinx.countersign.springai.ResumableGuardedToolCallingManager"
                                : GuardedToolCallingManager.class.getName());
    }

    private static boolean hasClass(String name) {
        try {
            Class.forName(name, false, ToolCallingManager.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    @Test
    void doesNotGuardTwice() {
        ToolCallingManager once =
                GuardedToolCallingManager.guard(ToolCallingManager.builder().build(), guard);

        assertThat(GuardedToolCallingManager.guard(once, guard)).isSameAs(once);
    }

    @Test
    void leavesToolDefinitionsToTheWrappedManager() {
        ToolCallingManager delegate = mock(ToolCallingManager.class);
        ToolCallingChatOptions options = ToolCallingChatOptions.builder().build();

        new GuardedToolCallingManager(delegate, () -> guard).resolveToolDefinitions(options);

        verify(delegate).resolveToolDefinitions(options);
    }

    @Test
    void failsClosedWhenNoGuardIsAvailable() {
        ToolCallingManager manager =
                new GuardedToolCallingManager(ToolCallingManager.builder().build(), () -> null);

        assertThatThrownBy(() -> manager.executeToolCalls(
                        promptWithBankTools(), modelCalls("payments.transfer", "{\"to\":\"ACME\",\"amount\":50}")))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("ToolCallGuard");
        assertThat(bank.transfers).isZero();
    }
}
