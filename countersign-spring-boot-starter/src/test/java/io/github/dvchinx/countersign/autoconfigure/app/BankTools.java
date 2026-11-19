package io.github.dvchinx.countersign.autoconfigure.app;

import io.github.dvchinx.countersign.springai.annotation.RequiresApproval;
import java.math.BigDecimal;
import org.springframework.ai.tool.annotation.Tool;

/** Tools in the application's package, never registered as a bean. They count how often they really ran. */
public class BankTools {

    public int transfers;
    public int balances;

    @Tool(name = "payments.transfer", description = "Transfer money to another account")
    @RequiresApproval(reason = "Moves real money")
    public String transfer(String to, BigDecimal amount) {
        transfers++;
        return "Transferred " + amount + " to " + to;
    }

    @Tool(name = "payments.balance", description = "Read the balance of an account")
    public String balance(String account) {
        balances++;
        return "1200.00";
    }
}
