package io.github.dvchinx.countersign.examples.banking;

import io.github.dvchinx.countersign.springai.GuardedToolCallback;
import java.util.Map;
import java.util.Objects;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

/**
 * The agent: a {@link ChatClient} with the bank's tools. Note what is missing: no Countersign code. The starter guards
 * every tool call this client makes.
 *
 * <p>The only Countersign-specific lines pass who the agent acts for and the conversation in the tool context, so
 * policies can use them and receipts record them.
 */
@Service
public class BankingAgent {

    private final ChatClient chat;
    private final BankTools tools;

    BankingAgent(ChatClient.Builder chat, BankTools tools) {
        this.chat = chat.build();
        this.tools = tools;
    }

    /**
     * Answers one message. Blocks while a tool call waits for human approval.
     *
     * @param user who the agent acts for
     * @param conversationId the conversation
     * @param message what the user asked
     * @return the agent's answer
     */
    public String chat(String user, String conversationId, String message) {
        String answer = chat.prompt()
                .user(message)
                .tools(tools)
                .toolContext(Map.of(
                        GuardedToolCallback.PRINCIPAL_KEY, user,
                        GuardedToolCallback.CONVERSATION_ID_KEY, conversationId))
                .call()
                .content();
        return Objects.requireNonNullElse(answer, "");
    }
}
