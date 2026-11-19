package io.github.dvchinx.countersign.springai.annotation.tools;

import io.github.dvchinx.countersign.springai.annotation.RequiresApproval;
import java.math.BigDecimal;
import org.springframework.ai.tool.annotation.Tool;

/** Test fixture: tools as an application would annotate them. */
public class AnnotatedTools {

    @Tool(name = "payments.transfer", description = "Transfer money")
    @RequiresApproval(reason = "Moves real money")
    public String transfer(String to, BigDecimal amount) {
        return "Transferred " + amount + " to " + to;
    }

    @Tool(description = "Close an account")
    @RequiresApproval
    public String closeAccount(String account) {
        return "Closed " + account;
    }

    @Tool(name = "payments.balance", description = "Read a balance")
    public String balance(String account) {
        return "42";
    }
}
