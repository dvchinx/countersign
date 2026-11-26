package io.github.dvchinx.countersign.springai;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dvchinx.countersign.core.guard.ToolCallGuard;
import io.github.dvchinx.countersign.core.model.Decision;
import io.github.dvchinx.countersign.core.model.JsonObject;
import io.github.dvchinx.countersign.core.model.Receipt;
import io.github.dvchinx.countersign.core.model.Redaction;
import io.github.dvchinx.countersign.core.policy.Policy;
import io.github.dvchinx.countersign.core.policy.PolicyEngine;
import io.github.dvchinx.countersign.core.receipt.ArgumentsHmac;
import io.github.dvchinx.countersign.core.receipt.Ed25519ReceiptSigner;
import io.github.dvchinx.countersign.core.receipt.ReceiptChain;
import io.github.dvchinx.countersign.core.receipt.ReceiptVerifier;
import io.github.dvchinx.countersign.core.redaction.PatternRedactor;
import io.github.dvchinx.countersign.core.redaction.RedactionRule;
import io.github.dvchinx.countersign.springai.micrometer.MicrometerDecisionListener;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * Argument redaction through a real {@link ChatClient} tool call, with a scripted model: what the tool received, and
 * what the receipt kept (ADR 0015).
 */
class ArgumentRedactionChatClientTest {

    /** A search tool that sends its query to an outside service; it remembers what it was asked. */
    static class SearchTools {

        final List<String> queries = new ArrayList<>();

        @Tool(name = "web_search", description = "Search the web")
        String search(String query, int limit) {
            queries.add(query);
            return "%d results".formatted(limit);
        }
    }

    /** A tool that records the raw input it was called with, as an MCP tool would get it. */
    static final class RawTool implements ToolCallback {

        final List<String> inputs = new ArrayList<>();

        @Override
        public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder()
                    .name("web_search")
                    .description("raw")
                    .inputSchema("{}")
                    .build();
        }

        @Override
        public String call(String toolInput) {
            inputs.add(toolInput);
            return "ok";
        }
    }

    private static final ArgumentsHmac ARGS_HMAC = new ArgumentsHmac(new byte[32]);

    private static final RedactionRule NO_EMAILS_TO_SEARCH = RedactionRule.builder("no-emails-to-search")
            .tools("web_search")
            .target(Redaction.Target.ARGS)
            .redactors(PatternRedactor.of("[\\w.+-]+@[\\w-]+(?:\\.[\\w-]+)+", "[EMAIL]"))
            .build();

    private static final PolicyEngine ENGINE = new PolicyEngine(
            List.of(Policy.builder("searches")
                    .tools("web_search")
                    .decision(Decision.allow())
                    .build()),
            Decision.deny("No policy allows this call"));

    private final List<Receipt> receipts = new ArrayList<>();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    private ToolCallGuard.Builder guard() {
        return ToolCallGuard.builder(
                        ENGINE,
                        ReceiptChain.start(
                                "search-1",
                                new Ed25519ReceiptSigner(BankPolicies.KEYS.getPrivate()),
                                ARGS_HMAC,
                                Clock.systemUTC()),
                        receipts::add)
                .listener(new MicrometerDecisionListener(meters))
                .redaction(NO_EMAILS_TO_SEARCH);
    }

    private static String ask(ScriptedChatModel model, ToolCallGuard guard, ToolCallback... tools) {
        return ChatClient.create(model)
                .prompt("search")
                .tools((Object[]) GuardedToolCallback.guardAll(guard, tools))
                .call()
                .content();
    }

    @Test
    void theSearchServiceNeverReceivesTheEmail() {
        SearchTools search = new SearchTools();
        String proposed = "{\"query\":\"orders of ana@example.com\",\"limit\":5}";

        assertThat(ask(new ScriptedChatModel("web_search", proposed), guard().build(), ToolCallbacks.from(search)))
                .isEqualTo("done");

        assertThat(search.queries).containsExactly("orders of [EMAIL]");
        assertThat(receipts).singleElement().satisfies(receipt -> {
            assertThat(receipt.redactions()).containsExactly("no-emails-to-search");
            // The receipt keeps the hash of what the model proposed, which the policies judged.
            assertThat(receipt.argsHmac())
                    .isEqualTo(ARGS_HMAC.hex(JsonObject.builder()
                            .put("query", "orders of ana@example.com")
                            .put("limit", 5)
                            .build()));
        });
        assertThat(meters.counter(
                                MicrometerDecisionListener.REDACTIONS,
                                "tool",
                                "web_search",
                                "rule",
                                "no-emails-to-search",
                                "target",
                                "args")
                        .count())
                .isEqualTo(1);
    }

    @Test
    void passesRedactedArgumentsAsCompactJsonThatKeepsNumbersExact() {
        RawTool tool = new RawTool();

        ask(
                new ScriptedChatModel(
                        "web_search", "{ \"query\": \"mail ana@example.com\", \"limit\": 5, \"budget\": 1000.50 }"),
                guard().build(),
                tool);

        assertThat(tool.inputs).containsExactly("{\"query\":\"mail [EMAIL]\",\"limit\":5,\"budget\":1000.50}");
    }

    @Test
    void passesTheOriginalInputByteForByteWhenNothingIsRedacted() {
        RawTool tool = new RawTool();
        String proposed = "{ \"query\" : \"weather in Bogotá\" , \"limit\": 5 }";

        ask(new ScriptedChatModel("web_search", proposed), guard().build(), tool);

        assertThat(tool.inputs).containsExactly(proposed);
        assertThat(receipts.getFirst().redactions()).containsExactly("no-emails-to-search");
    }

    @Test
    void refusesTheCallWhenTheArgumentsCannotBeRedacted() {
        RawTool tool = new RawTool();
        RedactionRule failing = RedactionRule.builder("broken-detector")
                .tools("web_search")
                .target(Redaction.Target.ARGS)
                .redactors(value -> {
                    throw new IllegalStateException("detector crashed");
                })
                .build();
        ScriptedChatModel model = new ScriptedChatModel("web_search", "{\"query\":\"ana@example.com\"}");

        ask(model, guard().redaction(failing).build(), tool);

        assertThat(tool.inputs).as("the tool never runs").isEmpty();
        assertThat(model.toolResult())
                .contains("\"status\":\"REFUSED\"", "\"decision\":\"DENY\"")
                .doesNotContain("ana@example.com", "detector");
        assertThat(receipts.getFirst().matchedPolicies()).containsExactly("broken-detector");
    }

    @Test
    void everyCallWithRedactedArgumentsLeavesAVerifiableReceipt() {
        ToolCallGuard guard = guard().build();
        SearchTools search = new SearchTools();
        ask(
                new ScriptedChatModel("web_search", "{\"query\":\"ana@example.com\",\"limit\":1}"),
                guard,
                ToolCallbacks.from(search));
        ask(
                new ScriptedChatModel("web_search", "{\"query\":\"weather\",\"limit\":1}"),
                guard,
                ToolCallbacks.from(search));

        assertThat(search.queries).containsExactly("[EMAIL]", "weather");
        assertThat(new ReceiptVerifier(BankPolicies.KEYS.getPublic())
                        .verify(receipts)
                        .isValid())
                .isTrue();
    }
}
