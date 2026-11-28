package io.github.dvchinx.countersign.examples.banking;

import io.github.dvchinx.countersign.core.approval.InMemoryApprovalGateway;
import io.github.dvchinx.countersign.core.audit.InMemoryAuditSink;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * Flagship demo: an agent that reads balances and moves money, with large transfers sent for human approval,
 * destructive SQL refused, and card numbers masked before they reach the model or an outside helpdesk. Every tool call
 * leaves a signed receipt.
 *
 * <p>Run it with {@code ./mvnw -pl examples/banking-agent spring-boot:run} and follow the example's README.
 */
@SpringBootApplication
public class BankingAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(BankingAgentApplication.class, args);
    }

    /** The scripted model. Replace it with a real one to talk to an actual LLM. */
    @Bean
    ChatModel demoChatModel() {
        return new DemoChatModel();
    }

    /** Calls that need approval wait here until someone answers through {@code /approvals}. */
    @Bean
    InMemoryApprovalGateway approvals() {
        return new InMemoryApprovalGateway();
    }

    /** Receipts kept in memory, so {@code /receipts} can show them. Use the PostgreSQL sink in production. */
    @Bean
    InMemoryAuditSink receipts() {
        return new InMemoryAuditSink();
    }
}
