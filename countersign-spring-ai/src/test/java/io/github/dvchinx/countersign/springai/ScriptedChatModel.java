package io.github.dvchinx.countersign.springai;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

/**
 * A chat model for tests: on its first turn it requests one tool call, on the next it answers "done". It keeps every
 * prompt, so a test can read what the tool returned to the model. No real model API is ever called.
 */
final class ScriptedChatModel implements ChatModel {

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
