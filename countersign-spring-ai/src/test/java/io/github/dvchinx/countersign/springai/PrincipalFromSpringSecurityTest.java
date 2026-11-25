package io.github.dvchinx.countersign.springai;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dvchinx.countersign.core.guard.ToolCallGuard;
import io.github.dvchinx.countersign.core.model.Receipt;
import io.github.dvchinx.countersign.core.receipt.Ed25519ReceiptSigner;
import io.github.dvchinx.countersign.springai.security.SpringSecurityPrincipalResolver;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import reactor.core.publisher.Hooks;

/**
 * Real tool calls through a {@link ChatClient}, with the signed-in Spring Security user as the principal: what
 * reaches the receipt with {@code call()}, and with {@code stream()}, where Spring AI runs the tools on a Reactor
 * worker thread.
 */
class PrincipalFromSpringSecurityTest {

    private final BankTools bank = new BankTools();
    private final List<Receipt> receipts = new CopyOnWriteArrayList<>();
    private final ToolCallGuard guard = BankPolicies.builder(
                    new Ed25519ReceiptSigner(BankPolicies.KEYS.getPrivate()), receipts::add)
            .principalResolver(new SpringSecurityPrincipalResolver())
            .build();

    @BeforeEach
    void signIn() {
        SecurityContextHolder.getContext()
                .setAuthentication(UsernamePasswordAuthenticationToken.authenticated("alice", null, List.of()));
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    private ChatClient.ChatClientRequestSpec transfer(ScriptedChatModel model) {
        return ChatClient.create(model).prompt("pay ACME").tools((Object[])
                GuardedToolCallback.guardAll(guard, ToolCallbacks.from(bank)));
    }

    private static ScriptedChatModel transferModel() {
        return new ScriptedChatModel("payments.transfer", "{\"to\":\"ACME\",\"amount\":50}");
    }

    @Test
    void recordsTheSignedInUserWithoutAnyToolContext() {
        assertThat(transfer(transferModel()).call().content()).isEqualTo("done");

        assertThat(bank.transfers).isEqualTo(1);
        assertThat(receipts)
                .singleElement()
                .satisfies(receipt -> assertThat(receipt.principal()).isEqualTo("alice"));
    }

    @Test
    void prefersThePrincipalInTheToolContextOverTheSignedInUser() {
        transfer(transferModel())
                .toolContext(Map.of(GuardedToolCallback.PRINCIPAL_KEY, "service-account"))
                .call()
                .content();

        assertThat(receipts)
                .singleElement()
                .satisfies(receipt -> assertThat(receipt.principal()).isEqualTo("service-account"));
    }

    @Test
    void losesTheSignedInUserWhenStreamingWithoutContextPropagation() {
        assertThat(transfer(transferModel()).stream().content().collectList().block())
                .contains("done");

        // Spring AI runs the tools on a Reactor worker thread, which has no security context of its own.
        assertThat(bank.transfers).isEqualTo(1);
        assertThat(receipts)
                .singleElement()
                .satisfies(receipt -> assertThat(receipt.principal()).isNull());
    }

    @Test
    void recordsTheSignedInUserWhenStreamingWithAutomaticContextPropagation() {
        Hooks.enableAutomaticContextPropagation();
        try {
            assertThat(transfer(transferModel()).stream()
                            .content()
                            .collectList()
                            .block())
                    .contains("done");
        } finally {
            Hooks.disableAutomaticContextPropagation();
        }

        assertThat(receipts)
                .singleElement()
                .satisfies(receipt -> assertThat(receipt.principal()).isEqualTo("alice"));
    }
}
