package io.github.dvchinx.countersign.examples.mcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Guarding tools exposed through an MCP server. */
@SpringBootApplication
public class GuardedMcpServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(GuardedMcpServerApplication.class, args);
    }
}
