package io.github.dvchinx.countersign.examples.banking;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.StatementCallback;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The agent's tools: plain Spring AI {@code @Tool} methods with no Countersign code in them.
 *
 * <p>They really change the in-memory bank. {@code db_query} runs whatever SQL it is given, so a {@code DROP TABLE}
 * would drop the table: what stops it is the {@code no-destructive-sql} policy, not the tool.
 */
@Component
public class BankTools {

    private static final int MAX_ROWS = 20;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    BankTools(JdbcTemplate jdbc, TransactionTemplate transaction) {
        this.jdbc = jdbc;
        this.transaction = transaction;
    }

    /**
     * Returns an account's balance.
     *
     * @param account the account id
     * @return the balance, as a sentence for the model
     */
    @Tool(name = "accounts_balance", description = "Get the balance of one of the customer's accounts")
    public String balance(@ToolParam(description = "Account id: checking or savings") String account) {
        List<BigDecimal> balance =
                jdbc.queryForList("SELECT balance FROM accounts WHERE id = ?", BigDecimal.class, account);
        return balance.isEmpty()
                ? "There is no account called " + account
                : "The " + account + " balance is " + money(balance.getFirst());
    }

    /**
     * Moves money from one of the customer's accounts to another account or an outside payee.
     *
     * @param from the account to debit
     * @param to an account id, or the name of an outside payee
     * @param amount how much, in dollars
     * @return what happened, as a sentence for the model
     */
    @Tool(
            name = "payments_transfer",
            description = "Transfer money from one of the customer's accounts to another account or a payee")
    public String transfer(
            @ToolParam(description = "Account to debit: checking or savings") String from,
            @ToolParam(description = "Account id or payee name to credit") String to,
            @ToolParam(description = "Amount in dollars") BigDecimal amount) {
        if (amount.signum() <= 0) {
            return "The amount must be positive";
        }
        BigDecimal cents = amount.setScale(2, RoundingMode.HALF_EVEN);
        return transaction.execute(status -> {
            List<BigDecimal> balance =
                    jdbc.queryForList("SELECT balance FROM accounts WHERE id = ? FOR UPDATE", BigDecimal.class, from);
            if (balance.isEmpty()) {
                return "There is no account called " + from;
            }
            if (balance.getFirst().compareTo(cents) < 0) {
                return "Insufficient funds: the " + from + " balance is " + money(balance.getFirst());
            }
            jdbc.update("UPDATE accounts SET balance = balance - ? WHERE id = ?", cents, from);
            jdbc.update("UPDATE accounts SET balance = balance + ? WHERE id = ?", cents, to);
            jdbc.update(
                    "INSERT INTO transactions (from_account, to_account, amount) VALUES (?, ?, ?)", from, to, cents);
            return "Transferred " + money(cents) + " from " + from + " to " + to + ". The " + from + " balance is now "
                    + money(balance.getFirst().subtract(cents));
        });
    }

    /**
     * Runs one SQL statement against the bank database.
     *
     * @param query the statement
     * @return the first rows, or how many rows changed
     */
    @Tool(name = "db_query", description = "Run a SQL statement against the bank database")
    public String query(@ToolParam(description = "One SQL statement") String query) {
        try {
            return jdbc.execute((StatementCallback<String>) statement -> statement.execute(query)
                    ? rows(statement.getResultSet())
                    : statement.getUpdateCount() + " rows affected");
        } catch (DataAccessException e) {
            return "The database refused the statement: "
                    + e.getMostSpecificCause().getMessage();
        }
    }

    private static String rows(ResultSet rs) throws SQLException {
        ResultSetMetaData columns = rs.getMetaData();
        List<String> rows = new ArrayList<>();
        while (rs.next() && rows.size() < MAX_ROWS) {
            List<String> values = new ArrayList<>();
            for (int i = 1; i <= columns.getColumnCount(); i++) {
                values.add(columns.getColumnLabel(i).toLowerCase(Locale.ROOT) + "=" + rs.getObject(i));
            }
            rows.add(String.join(", ", values));
        }
        return rows.isEmpty() ? "No rows" : String.join("\n", rows);
    }

    static String money(BigDecimal amount) {
        return NumberFormat.getCurrencyInstance(Locale.US).format(amount);
    }
}
