package io.github.dvchinx.countersign.springai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.dvchinx.countersign.core.guard.ToolCallGuard;
import io.github.dvchinx.countersign.core.model.Decision;
import io.github.dvchinx.countersign.core.model.Receipt;
import io.github.dvchinx.countersign.core.policy.Policy;
import io.github.dvchinx.countersign.core.policy.PolicyEngine;
import io.github.dvchinx.countersign.core.receipt.ArgumentsHmac;
import io.github.dvchinx.countersign.core.receipt.Ed25519ReceiptSigner;
import io.github.dvchinx.countersign.core.receipt.ReceiptChain;
import java.security.KeyPairGenerator;
import java.security.Principal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

class GuardedToolCallbackTest {

    private final List<Receipt> receipts = new ArrayList<>();
    private final AtomicInteger runs = new AtomicInteger();

    private ToolCallback tool(String name, RuntimeException failure) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                        .name(name)
                        .description("test tool")
                        .inputSchema("{}")
                        .build();
            }

            @Override
            public ToolMetadata getToolMetadata() {
                return ToolMetadata.builder().returnDirect(true).build();
            }

            @Override
            public String call(String toolInput) {
                runs.incrementAndGet();
                if (failure != null) {
                    throw failure;
                }
                return "ran with " + toolInput;
            }
        };
    }

    private ToolCallback tool(String name) {
        return tool(name, null);
    }

    private ToolCallGuard guard(Decision decision) throws Exception {
        var keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        return new ToolCallGuard(
                new PolicyEngine(
                        List.of(Policy.builder("p")
                                .tools("*")
                                .decision(decision)
                                .build()),
                        Decision.deny("default")),
                ReceiptChain.start(
                        "s",
                        new Ed25519ReceiptSigner(keys.getPrivate()),
                        new ArgumentsHmac(new byte[32]),
                        Clock.systemUTC()),
                receipts::add,
                false);
    }

    @Test
    void exposesTheWrappedToolsDefinitionAndMetadata() throws Exception {
        ToolCallback original = tool("payments.transfer");
        ToolCallback guarded = GuardedToolCallback.guard(original, guard(Decision.allow()));

        assertThat(guarded.getToolDefinition()).isEqualTo(original.getToolDefinition());
        assertThat(guarded.getToolMetadata().returnDirect()).isTrue();
        assertThat(((GuardedToolCallback) guarded).getDelegate()).isSameAs(original);
        assertThat(guarded.toString()).contains("payments.transfer");
    }

    @Test
    void doesNotWrapTwice() throws Exception {
        ToolCallGuard guard = guard(Decision.allow());
        ToolCallback once = GuardedToolCallback.guard(tool("t"), guard);

        assertThat(GuardedToolCallback.guard(once, guard)).isSameAs(once);
        once.call("{}");
        assertThat(receipts).hasSize(1);
    }

    @Test
    void passesTheOriginalInputToTheToolWhenAllowed() throws Exception {
        ToolCallback guarded = GuardedToolCallback.guard(tool("t"), guard(Decision.allow()));

        assertThat(guarded.call("{\"b\":1, \"a\":2}")).isEqualTo("ran with {\"b\":1, \"a\":2}");
    }

    @Test
    void passesToolExceptionsOnUntouched() throws Exception {
        IllegalStateException failure = new IllegalStateException("tool bug");
        ToolCallback guarded = GuardedToolCallback.guard(tool("t", failure), guard(Decision.allow()));

        assertThatThrownBy(() -> guarded.call("{}")).isSameAs(failure);
    }

    @Test
    void returnsAStructuredRefusalInsteadOfRunningTheTool() throws Exception {
        ToolCallback guarded = GuardedToolCallback.guard(tool("db.query"), guard(Decision.deny("No SQL from agents")));

        String result = guarded.call("{\"sql\":\"DROP TABLE accounts\"}");

        assertThat(runs).hasValue(0);
        assertThat(result)
                .startsWith("{\"decision\":\"DENY\",\"invocationId\":\"")
                .contains("\"reason\":\"No SQL from agents\"", "\"status\":\"REFUSED\"", "\"tool\":\"db.query\"")
                .doesNotContain("DROP");
        assertThat(receipts.getFirst().invocationId())
                .isEqualTo(result.replaceAll(".*\"invocationId\":\"([^\"]+)\".*", "$1"));
    }

    @Test
    void refusesWhenCountersignItselfFailsUnexpectedly() {
        ToolCallGuard broken = mock(ToolCallGuard.class);
        when(broken.check(any())).thenThrow(new IllegalStateException("secret internal detail"));
        ToolCallback guarded = GuardedToolCallback.guard(tool("t"), broken);

        String result = guarded.call("{}");

        assertThat(runs).hasValue(0);
        assertThat(result).contains("REFUSED").doesNotContain("secret internal detail");
    }

    @Test
    void recordsThePrincipalAndConversationFromTheToolContext() throws Exception {
        ToolCallback guarded = GuardedToolCallback.guard(tool("t"), guard(Decision.allow()));
        Principal alice = () -> "alice";

        guarded.call(
                "{}",
                new ToolContext(
                        Map.of(GuardedToolCallback.PRINCIPAL_KEY, alice, GuardedToolCallback.CONVERSATION_ID_KEY, 42)));
        guarded.call("{}", new ToolContext(Map.of(GuardedToolCallback.PRINCIPAL_KEY, "bob")));
        guarded.call("{}");

        assertThat(receipts).extracting(Receipt::principal).containsExactly("alice", "bob", null);
        assertThat(receipts.getFirst().conversationId()).isEqualTo("42");
    }

    @Test
    void providerGuardsToolsAddedAfterItWasCreated() throws Exception {
        List<ToolCallback> current = new ArrayList<>(List.of(tool("first")));
        ToolCallbackProvider provider =
                new GuardedToolCallbackProvider(() -> current.toArray(ToolCallback[]::new), guard(Decision.allow()));

        current.add(tool("added.later"));

        assertThat(provider.getToolCallbacks()).hasSize(2).allMatch(GuardedToolCallback.class::isInstance);
    }
}
