package io.github.dvchinx.countersign.core.approval;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dvchinx.countersign.core.model.ApprovalDecision;
import io.github.dvchinx.countersign.core.model.ApprovalRequest;
import io.github.dvchinx.countersign.core.model.JsonObject;
import io.github.dvchinx.countersign.core.model.ToolInvocation;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class InMemoryApprovalGatewayTest {

    private final InMemoryApprovalGateway gateway = new InMemoryApprovalGateway();

    private static ApprovalRequest request(String id, Instant deadline) {
        return new ApprovalRequest(
                new ToolInvocation(id, "payments.transfer", JsonObject.empty(), "alice", null, Instant.EPOCH),
                "transfers-need-approval",
                "Moves real money",
                deadline);
    }

    @Test
    void listsPendingRequestsClosestDeadlineFirst() {
        gateway.request(request("late", Instant.parse("2026-11-19T10:05:00Z")));
        gateway.request(request("soon", Instant.parse("2026-11-19T10:01:00Z")));

        assertThat(gateway.pending()).extracting(r -> r.invocation().id()).containsExactly("soon", "late");
    }

    @Test
    void approvingAnswersTheRequestAndRemovesIt() {
        CompletableFuture<ApprovalDecision> answer =
                gateway.request(request("inv-1", Instant.MAX)).toCompletableFuture();

        assertThat(gateway.approve("inv-1", "bob")).isTrue();

        assertThat(answer).isCompletedWithValue(ApprovalDecision.approved("bob"));
        assertThat(gateway.pending()).isEmpty();
        assertThat(gateway.approve("inv-1", "carol")).isFalse();
    }

    @Test
    void rejectingAnswersTheRequestWithTheReason() {
        CompletableFuture<ApprovalDecision> answer =
                gateway.request(request("inv-1", Instant.MAX)).toCompletableFuture();

        assertThat(gateway.reject("inv-1", "bob", "Unknown payee")).isTrue();

        assertThat(answer).isCompletedWithValue(ApprovalDecision.rejected("bob", "Unknown payee"));
    }

    @Test
    void cannotAnswerAnUnknownRequest() {
        assertThat(gateway.approve("nope", "bob")).isFalse();
        assertThat(gateway.reject("nope", "bob", null)).isFalse();
    }

    @Test
    void forgetsARequestThatCountersignWithdrew() {
        CompletableFuture<ApprovalDecision> answer =
                gateway.request(request("inv-1", Instant.MAX)).toCompletableFuture();

        answer.cancel(false);

        assertThat(gateway.pending()).isEmpty();
        assertThat(gateway.approve("inv-1", "bob")).isFalse();
    }

    @Test
    void refusesASecondRequestForTheSameInvocation() {
        gateway.request(request("inv-1", Instant.MAX));

        assertThat(gateway.request(request("inv-1", Instant.MAX)).toCompletableFuture())
                .isCompletedExceptionally();
        assertThat(gateway.pending()).hasSize(1);
    }

    @Test
    void answerFromAnotherThreadReachesTheWaitingCall() throws InterruptedException {
        CompletableFuture<ApprovalDecision> answer =
                gateway.request(request("inv-1", Instant.MAX)).toCompletableFuture();

        Thread.ofVirtual().start(() -> gateway.approve("inv-1", "bob")).join();

        assertThat(answer).isCompletedWithValue(ApprovalDecision.approved("bob"));
    }
}
