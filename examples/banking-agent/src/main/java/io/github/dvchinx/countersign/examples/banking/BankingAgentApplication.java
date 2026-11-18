package io.github.dvchinx.countersign.examples.banking;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Flagship demo: an agent that reads balances and moves money, with large transfers sent for human approval. */
@SpringBootApplication
public class BankingAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(BankingAgentApplication.class, args);
    }
}
