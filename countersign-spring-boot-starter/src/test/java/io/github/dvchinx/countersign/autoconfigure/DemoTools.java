package io.github.dvchinx.countersign.autoconfigure;

import java.math.BigDecimal;
import org.springframework.ai.tool.annotation.Tool;

/** Plain tools, matching the names used in the README; they count how often they really ran. */
class DemoTools {

    int transfers;
    int queries;

    @Tool(name = "payments_transfer", description = "Transfer money to another account")
    String transfer(String to, BigDecimal amount) {
        transfers++;
        return "Transferred " + amount + " to " + to;
    }

    @Tool(name = "db_query", description = "Run a SQL query")
    String query(String query) {
        queries++;
        return "3 rows";
    }
}
