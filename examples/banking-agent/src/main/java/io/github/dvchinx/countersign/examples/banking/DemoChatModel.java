package io.github.dvchinx.countersign.examples.banking;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A scripted stand-in for a real model, so the demo runs with no API key and behaves the same every time.
 *
 * <p>It understands a few banking requests and turns them into tool calls, exactly as a real model would; the call
 * then goes through Spring AI's tool loop and Countersign like any other. When the tool answers, it reports the result,
 * or explains Countersign's refusal. Replace this bean with a real {@code ChatModel} (see the example's README) and
 * nothing else changes.
 */
final class DemoChatModel implements ChatModel {

    /** A tool call the model decided to make. */
    record PlannedCall(String tool, Map<String, Object> arguments) {}

    private static final JsonMapper JSON = JsonMapper.shared();

    private static final Pattern SQL = Pattern.compile("(?is)^\\s*(select|drop|delete|truncate|update|insert)\\b.*");
    private static final Pattern DROP_TABLE = Pattern.compile("(?i)\\bdrop(?:ping)?\\b.*?\\b(\\w+)\\s+table\\b");
    private static final Pattern TRANSFER = Pattern.compile("(?i)\\b(move|transfer|send|pay)\\b");
    private static final Pattern AMOUNT = Pattern.compile("\\$?\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)");
    private static final Pattern FROM = Pattern.compile("(?i)\\bfrom\\s+(?:my\\s+|the\\s+)?(\\w+)");
    private static final Pattern TO =
            Pattern.compile("(?i)\\bto\\s+(?:my\\s+|the\\s+)?(.+?)(?:\\s+from\\b.*)?\\s*[.!?]*$");
    private static final Pattern BALANCE = Pattern.compile("(?i)\\bbalance\\b");
    private static final Pattern LIST = Pattern.compile("(?i)\\b(?:show|list)\\b.*\\b(transactions|cards)\\b");
    private static final Pattern TICKET = Pattern.compile(
            "(?is)^\\s*(?:please\\s+)?(?:open|file|raise)\\s+(?:a\\s+)?(?:support\\s+)?ticket\\b\\s*[:,-]?\\s*(.+)$");

    static final String HELP = "I'm the demo's scripted model. Try \"What's my checking balance?\", \"Move $5,000 from"
            + " checking to ACME Corp\", \"Show me my cards\", \"Open a support ticket: …\" or \"Drop the transactions"
            + " table\".";

    private final AtomicLong callIds = new AtomicLong();

    @Override
    public ChatResponse call(Prompt prompt) {
        List<Message> messages = prompt.getInstructions();
        Message last = messages.getLast();
        if (last instanceof ToolResponseMessage response) {
            return answer(explain(response.getResponses().getFirst().responseData()));
        }
        Optional<PlannedCall> planned = plan(last.getText());
        if (planned.isEmpty()) {
            return answer(HELP);
        }
        AssistantMessage.ToolCall call = new AssistantMessage.ToolCall(
                "call-" + callIds.incrementAndGet(),
                "function",
                planned.get().tool(),
                JSON.writeValueAsString(planned.get().arguments()));
        return new ChatResponse(List.of(new Generation(
                AssistantMessage.builder().content("").toolCalls(List.of(call)).build())));
    }

    @Override
    public ChatOptions getOptions() {
        return ToolCallingChatOptions.builder().build();
    }

    /** Decides which tool to call for a request, if any. */
    static Optional<PlannedCall> plan(@Nullable String request) {
        if (request == null || request.isBlank()) {
            return Optional.empty();
        }
        String text = request.strip();
        // First: a ticket's description is free text and may mention a transfer, a balance or a table.
        Matcher ticket = TICKET.matcher(text);
        if (ticket.matches()) {
            return Optional.of(new PlannedCall(
                    "support_open_ticket", Map.of("description", ticket.group(1).strip())));
        }
        if (SQL.matcher(text).matches()) {
            return Optional.of(new PlannedCall("db_query", Map.of("query", text.replaceAll(";\\s*$", ""))));
        }
        Matcher drop = DROP_TABLE.matcher(text);
        if (drop.find()) {
            return Optional.of(new PlannedCall("db_query", Map.of("query", "DROP TABLE " + drop.group(1))));
        }
        Matcher amount = AMOUNT.matcher(text);
        Matcher to = TO.matcher(text);
        if (TRANSFER.matcher(text).find() && amount.find() && to.find()) {
            Map<String, Object> arguments = new LinkedHashMap<>();
            Matcher from = FROM.matcher(text);
            arguments.put("from", from.find() ? from.group(1).toLowerCase(Locale.ROOT) : "checking");
            arguments.put("to", to.group(1).strip());
            arguments.put("amount", new BigDecimal(amount.group(1).replace(",", "")));
            return Optional.of(new PlannedCall("payments_transfer", arguments));
        }
        if (BALANCE.matcher(text).find()) {
            String account = text.toLowerCase(Locale.ROOT).contains("savings") ? "savings" : "checking";
            return Optional.of(new PlannedCall("accounts_balance", Map.of("account", account)));
        }
        Matcher list = LIST.matcher(text);
        if (list.find()) {
            String query = list.group(1).equalsIgnoreCase("cards")
                    ? "SELECT * FROM cards ORDER BY id"
                    : "SELECT * FROM transactions ORDER BY id DESC";
            return Optional.of(new PlannedCall("db_query", Map.of("query", query)));
        }
        return Optional.empty();
    }

    /** Turns what the tool returned, or Countersign's refusal, into an answer for the user. */
    static String explain(String toolResult) {
        try {
            JsonNode result = JSON.readTree(toolResult);
            if (result.isObject() && "REFUSED".equals(result.path("status").asString(""))) {
                return "I didn't do that: Countersign refused the call ("
                        + result.path("decision").asString("") + "). "
                        + result.path("reason").asString("");
            }
            if (result.isString()) {
                return result.stringValue();
            }
        } catch (JacksonException e) {
            // Not JSON: a plain answer.
        }
        return toolResult;
    }

    private static ChatResponse answer(String text) {
        return new ChatResponse(
                List.of(new Generation(AssistantMessage.builder().content(text).build())));
    }
}
