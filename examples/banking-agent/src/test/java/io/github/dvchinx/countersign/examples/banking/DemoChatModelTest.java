package io.github.dvchinx.countersign.examples.banking;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dvchinx.countersign.examples.banking.DemoChatModel.PlannedCall;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DemoChatModelTest {

    @Test
    void turnsATransferRequestIntoATransferCall() {
        assertThat(DemoChatModel.plan("Move $5,000 from checking to ACME Corp."))
                .contains(new PlannedCall(
                        "payments_transfer",
                        Map.of("from", "checking", "to", "ACME Corp", "amount", new BigDecimal("5000"))));
        assertThat(DemoChatModel.plan("send 50 to savings from my checking"))
                .contains(new PlannedCall(
                        "payments_transfer",
                        Map.of("from", "checking", "to", "savings", "amount", new BigDecimal("50"))));
    }

    @Test
    void turnsADropRequestIntoSql() {
        assertThat(DemoChatModel.plan("Great. Now clean up by dropping the transactions table."))
                .contains(new PlannedCall("db_query", Map.of("query", "DROP TABLE transactions")));
        assertThat(DemoChatModel.plan("select * from accounts;"))
                .contains(new PlannedCall("db_query", Map.of("query", "select * from accounts")));
    }

    @Test
    void asksForABalance() {
        assertThat(DemoChatModel.plan("What's my savings balance?"))
                .contains(new PlannedCall("accounts_balance", Map.of("account", "savings")));
    }

    @Test
    void listsCardsAndTransactions() {
        assertThat(DemoChatModel.plan("Show me my cards"))
                .contains(new PlannedCall("db_query", Map.of("query", "SELECT * FROM cards ORDER BY id")));
        assertThat(DemoChatModel.plan("List my transactions"))
                .contains(new PlannedCall("db_query", Map.of("query", "SELECT * FROM transactions ORDER BY id DESC")));
    }

    @Test
    void opensATicketWithTheDescriptionAsWritten() {
        assertThat(DemoChatModel.plan("Open a support ticket: I can't pay ACME Corp $50 from checking, drop it?"))
                .contains(new PlannedCall(
                        "support_open_ticket",
                        Map.of("description", "I can't pay ACME Corp $50 from checking, drop it?")));
    }

    @Test
    void callsNoToolForAnythingElse() {
        assertThat(DemoChatModel.plan("Tell me a joke")).isEmpty();
    }

    @Test
    void explainsARefusalAndPassesResultsThrough() {
        assertThat(DemoChatModel.explain(
                        "{\"decision\":\"DENY\",\"reason\":\"No SQL\",\"status\":\"REFUSED\",\"tool\":\"db_query\"}"))
                .isEqualTo("I didn't do that: Countersign refused the call (DENY). No SQL");
        assertThat(DemoChatModel.explain("\"The checking balance is $1.00\""))
                .isEqualTo("The checking balance is $1.00");
        assertThat(DemoChatModel.explain("not json")).isEqualTo("not json");
    }
}
