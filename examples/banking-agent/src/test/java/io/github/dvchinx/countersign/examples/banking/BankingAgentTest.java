package io.github.dvchinx.countersign.examples.banking;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dvchinx.countersign.core.approval.InMemoryApprovalGateway;
import io.github.dvchinx.countersign.core.audit.InMemoryAuditSink;
import io.github.dvchinx.countersign.core.model.ApprovalOutcome;
import io.github.dvchinx.countersign.core.model.ApprovalRequest;
import io.github.dvchinx.countersign.core.model.Decision;
import io.github.dvchinx.countersign.core.model.Receipt;
import io.github.dvchinx.countersign.core.receipt.ReceiptChain;
import io.github.dvchinx.countersign.core.receipt.ReceiptVerifier;
import io.github.dvchinx.countersign.core.receipt.VerificationResult;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** The demo's story, end to end: the agent, Spring AI's tool loop, Countersign, the bank and the receipts. */
@SpringBootTest
class BankingAgentTest {

    @Autowired
    BankingAgent agent;

    @Autowired
    InMemoryApprovalGateway approvals;

    @Autowired
    InMemoryAuditSink receipts;

    @Autowired
    ReceiptChain chain;

    @Autowired
    ReceiptVerifier verifier;

    @Autowired
    JdbcTemplate jdbc;

    private String ask(String message) {
        return agent.chat("alice", UUID.randomUUID().toString(), message);
    }

    private CompletableFuture<String> askInBackground(String message) {
        return CompletableFuture.supplyAsync(
                () -> ask(message), runnable -> Thread.ofVirtual().start(runnable));
    }

    private BigDecimal balance(String account) {
        return jdbc.queryForObject("SELECT balance FROM accounts WHERE id = ?", BigDecimal.class, account);
    }

    private int transactionsTo(String payee) {
        return jdbc.queryForObject("SELECT count(*) FROM transactions WHERE to_account = ?", Integer.class, payee);
    }

    private Receipt lastReceipt() {
        return receipts.stream(chain.streamId()).getLast();
    }

    /** Waits for the agent's call to show up on the approval screen, as a human would. */
    private ApprovalRequest awaitPendingApproval() throws InterruptedException {
        long giveUpAt = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < giveUpAt) {
            List<ApprovalRequest> pending = approvals.pending();
            if (!pending.isEmpty()) {
                return pending.getFirst();
            }
            Thread.sleep(10);
        }
        throw new AssertionError("No call was sent for approval");
    }

    @Test
    void readsABalanceWithoutAskingAnyone() {
        assertThat(ask("What's my savings balance?")).startsWith("The savings balance is $");

        assertThat(lastReceipt()).satisfies(receipt -> {
            assertThat(receipt.tool()).isEqualTo("accounts_balance");
            assertThat(receipt.decision()).isEqualTo(Decision.Type.ALLOW);
            assertThat(receipt.matchedPolicies()).containsExactly("everyday-banking");
            assertThat(receipt.principal()).isEqualTo("alice");
        });
    }

    @Test
    void movesASmallAmountWithoutApproval() {
        BigDecimal before = balance("checking");

        String answer = ask("Move $50 from checking to Corner Cafe");

        assertThat(answer).startsWith("Transferred $50.00 from checking to Corner Cafe");
        assertThat(balance("checking")).isEqualByComparingTo(before.subtract(new BigDecimal("50")));
        assertThat(lastReceipt().matchedPolicies()).containsExactly("everyday-banking");
    }

    @Test
    void waitsForASecondPersonBeforeALargeTransferAndRunsItOnceApproved() throws Exception {
        BigDecimal before = balance("checking");

        CompletableFuture<String> answer = askInBackground("Move $5,000 from checking to ACME Corp.");
        ApprovalRequest pending = awaitPendingApproval();

        assertThat(pending.invocation().toolName()).isEqualTo("payments_transfer");
        assertThat(pending.reason()).isEqualTo("Transfers above $1,000 need a second person");
        assertThat(answer).isNotDone();
        assertThat(balance("checking")).isEqualByComparingTo(before);

        approvals.approve(pending.invocation().id(), "ops@bank.example");

        assertThat(answer.get(10, TimeUnit.SECONDS)).startsWith("Transferred $5,000.00 from checking to ACME Corp");
        assertThat(balance("checking")).isEqualByComparingTo(before.subtract(new BigDecimal("5000")));
        assertThat(lastReceipt()).satisfies(receipt -> {
            assertThat(receipt.decision()).isEqualTo(Decision.Type.REQUIRE_APPROVAL);
            assertThat(receipt.matchedPolicies()).containsExactly("transfers-need-approval");
            assertThat(receipt.approval()).isEqualTo(ApprovalOutcome.APPROVED);
            assertThat(receipt.approver()).isEqualTo("ops@bank.example");
            assertThat(receipt.principal()).isEqualTo("alice");
        });
    }

    @Test
    void movesNoMoneyWhenTheApproverRejectsTheTransfer() throws Exception {
        BigDecimal before = balance("savings");

        CompletableFuture<String> answer = askInBackground("Send $20,000 from savings to Unknown Payee Ltd");
        approvals.reject(awaitPendingApproval().invocation().id(), "ops@bank.example", "Payee not on file");

        assertThat(answer.get(10, TimeUnit.SECONDS))
                .contains("Countersign refused the call (REQUIRE_APPROVAL)", "Payee not on file");
        assertThat(balance("savings")).isEqualByComparingTo(before);
        assertThat(transactionsTo("Unknown Payee Ltd")).isZero();
        assertThat(lastReceipt().approval()).isEqualTo(ApprovalOutcome.REJECTED);
    }

    @Test
    void refusesToDropATableAndTheTableSurvives() {
        String answer = ask("Great. Now clean up by dropping the transactions table.");

        assertThat(answer)
                .isEqualTo("I didn't do that: Countersign refused the call (DENY). Destructive SQL is not allowed from"
                        + " agents");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transactions", Integer.class))
                .isPositive();
        assertThat(lastReceipt()).satisfies(receipt -> {
            assertThat(receipt.tool()).isEqualTo("db_query");
            assertThat(receipt.decision()).isEqualTo(Decision.Type.DENY);
            assertThat(receipt.matchedPolicies()).containsExactly("no-destructive-sql");
        });
    }

    @Test
    void refusesSqlThatNoPolicyAllows() {
        BigDecimal before = balance("checking");

        String answer = ask("UPDATE accounts SET balance = 0");

        assertThat(answer).contains("(DENY)", "No policy allows this call");
        assertThat(balance("checking")).isEqualByComparingTo(before);
        assertThat(lastReceipt().matchedPolicies()).isEmpty();
    }

    @Test
    void letsTheAgentReadTheDatabase() {
        assertThat(ask("Show me the latest transactions")).contains("to_account=");
        assertThat(lastReceipt().matchedPolicies()).containsExactly("sql-reads");
    }

    @Test
    void keepsAReceiptTrailThatVerifies() {
        ask("What's my checking balance?");
        ask("Drop the transactions table");

        VerificationResult result = verifier.verify(receipts.stream(chain.streamId()));

        assertThat(result).isInstanceOf(VerificationResult.Valid.class);
    }
}
