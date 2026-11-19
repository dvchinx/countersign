package io.github.dvchinx.countersign.springai;

import java.math.BigDecimal;
import org.springframework.ai.tool.annotation.Tool;

/** Tools as a Spring app would write them, with no Countersign code; they count how often they really ran. */
class BankTools {

    int transfers;
    int queries;

    @Tool(name = "payments.transfer", description = "Transfer money to another account")
    String transfer(String to, BigDecimal amount) {
        transfers++;
        return "Transferred " + amount + " to " + to;
    }

    @Tool(name = "db.query", description = "Run a SQL query")
    String query(String sql) {
        queries++;
        return "3 rows";
    }
}
