package io.github.dvchinx.countersign.examples.banking;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dvchinx.countersign.core.approval.InMemoryApprovalGateway;
import io.github.dvchinx.countersign.core.model.ApprovalRequest;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** The HTTP API the README walks through: chat, approval screen, receipts and metrics. */
@SpringBootTest
@AutoConfigureMockMvc
class BankingApiTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    InMemoryApprovalGateway approvals;

    private MvcTestResult chat(String user, String message) {
        return mvc.post()
                .uri("/chat")
                .header("X-User", user)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"" + message + "\"}")
                .exchange();
    }

    private String awaitPendingId() throws InterruptedException {
        long giveUpAt = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < giveUpAt) {
            if (!approvals.pending().isEmpty()) {
                return approvals.pending().getFirst().invocation().id();
            }
            Thread.sleep(10);
        }
        throw new AssertionError("No call was sent for approval");
    }

    @Test
    void answersAChatMessage() {
        assertThat(chat("alice", "What's my checking balance?"))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.answer")
                .asString()
                .startsWith("The checking balance is $");
    }

    @Test
    void showsAPendingTransferAndLetsAnotherPersonApproveIt() throws Exception {
        CompletableFuture<MvcTestResult> reply = CompletableFuture.supplyAsync(
                () -> chat("alice", "Move $5,000 from checking to ACME Corp."),
                runnable -> Thread.ofVirtual().start(runnable));
        String id = awaitPendingId();

        assertThat(mvc.get().uri("/approvals")).hasStatusOk().bodyJson().satisfies(json -> {
            json.assertThat().extractingPath("$[0].tool").isEqualTo("payments.transfer");
            json.assertThat().extractingPath("$[0].arguments.to").isEqualTo("ACME Corp");
            json.assertThat().extractingPath("$[0].requestedBy").isEqualTo("alice");
        });
        assertThat(mvc.post().uri("/approvals/{id}/approve", id).header("X-User", "alice"))
                .hasStatus(HttpStatus.FORBIDDEN);
        assertThat(mvc.post().uri("/approvals/{id}/approve", id).header("X-User", "ops@bank.example"))
                .hasStatusOk();

        assertThat(reply.get(10, TimeUnit.SECONDS))
                .bodyJson()
                .extractingPath("$.answer")
                .asString()
                .startsWith("Transferred $5,000.00");
        assertThat(mvc.post().uri("/approvals/{id}/approve", id)).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void listsAndVerifiesTheReceipts() {
        chat("alice", "Drop the transactions table");

        assertThat(mvc.get().uri("/receipts"))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$[-1:].matchedPolicies[0]")
                .asArray()
                .containsExactly("no-destructive-sql");
        assertThat(mvc.get().uri("/receipts/verify"))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.valid")
                .isEqualTo(true);
    }

    @Test
    void publishesDecisionMetrics() {
        chat("alice", "Drop the transactions table");

        assertThat(mvc.get().uri("/actuator/metrics/countersign.decisions?tag=policy:no-destructive-sql"))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.measurements[0].value")
                .asNumber()
                .satisfies(count -> assertThat(count.doubleValue()).isPositive());
    }

    @Test
    void ignoresAnAnswerToAnUnknownApproval() {
        assertThat(mvc.post().uri("/approvals/{id}/reject", "no-such-call")).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(approvals.pending()).extracting(ApprovalRequest::reason).isEmpty();
    }
}
