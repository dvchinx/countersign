package io.github.dvchinx.countersign.examples.banking;

import io.github.dvchinx.countersign.core.approval.InMemoryApprovalGateway;
import io.github.dvchinx.countersign.core.audit.InMemoryAuditSink;
import io.github.dvchinx.countersign.core.model.ApprovalRequest;
import io.github.dvchinx.countersign.core.model.Receipt;
import io.github.dvchinx.countersign.core.receipt.ReceiptChain;
import io.github.dvchinx.countersign.core.receipt.ReceiptVerifier;
import io.github.dvchinx.countersign.core.receipt.VerificationResult;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * The demo's HTTP API: talk to the agent, answer approvals, read and verify receipts, and see what the outside
 * helpdesk received.
 *
 * <p>The caller is named in an {@code X-User} header so the demo needs no login. A real application takes the
 * identity from Spring Security instead; the approver's name ends up in a signed receipt, so it must not be something
 * the caller can choose.
 */
@RestController
class BankingApi {

    private final BankingAgent agent;
    private final InMemoryApprovalGateway approvals;
    private final InMemoryAuditSink receipts;
    private final ReceiptChain chain;
    private final ReceiptVerifier verifier;
    private final JdbcTemplate jdbc;

    BankingApi(
            BankingAgent agent,
            InMemoryApprovalGateway approvals,
            InMemoryAuditSink receipts,
            ReceiptChain chain,
            ReceiptVerifier verifier,
            JdbcTemplate jdbc) {
        this.agent = agent;
        this.approvals = approvals;
        this.receipts = receipts;
        this.chain = chain;
        this.verifier = verifier;
        this.jdbc = jdbc;
    }

    record ChatRequest(String message, @Nullable String conversationId) {}

    record ChatReply(String conversationId, String answer) {}

    record PendingApproval(
            String invocationId,
            String tool,
            Map<String, @Nullable Object> arguments,
            String reason,
            @Nullable String requestedBy,
            Instant deadline) {

        static PendingApproval of(ApprovalRequest request) {
            return new PendingApproval(
                    request.invocation().id(),
                    request.invocation().toolName(),
                    request.invocation().arguments().toJava(),
                    request.reason(),
                    request.invocation().principal(),
                    request.deadline());
        }
    }

    record Rejection(@Nullable String reason) {}

    record Ticket(long id, String description) {}

    record Verification(
            boolean valid,
            String streamId,
            int receipts,
            @Nullable String problem) {}

    /** Talks to the agent. Waits while a tool call is pending approval. */
    @PostMapping("/chat")
    ChatReply chat(
            @RequestHeader(name = "X-User", defaultValue = "alice") String user, @RequestBody ChatRequest request) {
        String conversationId = request.conversationId() != null
                ? request.conversationId()
                : UUID.randomUUID().toString();
        return new ChatReply(conversationId, agent.chat(user, conversationId, request.message()));
    }

    /** The approval screen: calls waiting for a human, with what they would do and why they need approval. */
    @GetMapping("/approvals")
    List<PendingApproval> pending() {
        return approvals.pending().stream().map(PendingApproval::of).toList();
    }

    @PostMapping("/approvals/{invocationId}/approve")
    ResponseEntity<String> approve(
            @RequestHeader(name = "X-User", defaultValue = "ops@bank.example") String approver,
            @PathVariable String invocationId) {
        return answer(invocationId, approver, () -> approvals.approve(invocationId, approver), "approved");
    }

    @PostMapping("/approvals/{invocationId}/reject")
    ResponseEntity<String> reject(
            @RequestHeader(name = "X-User", defaultValue = "ops@bank.example") String approver,
            @PathVariable String invocationId,
            @RequestBody(required = false) @Nullable Rejection rejection) {
        return answer(
                invocationId,
                approver,
                () -> approvals.reject(invocationId, approver, rejection == null ? null : rejection.reason()),
                "rejected");
    }

    /** The receipts of this run, oldest first. */
    @GetMapping("/receipts")
    List<Receipt> receipts() {
        return receipts.stream(chain.streamId());
    }

    /** Checks every receipt of this run: hashes, chain links and signatures. */
    @GetMapping("/receipts/verify")
    Verification verify() {
        return switch (verifier.verify(receipts.stream(chain.streamId()))) {
            case VerificationResult.Valid valid ->
                new Verification(true, chain.streamId(), valid.receiptsChecked(), null);
            case VerificationResult.Invalid invalid ->
                new Verification(
                        false,
                        chain.streamId(),
                        invalid.index(),
                        "receipt #" + invalid.sequence() + ": " + invalid.problem());
        };
    }

    /**
     * The tickets the outside helpdesk received, newest first. Read straight from its table, not through the agent, so
     * what shows here is what left the bank: no redaction rule applies to this endpoint.
     */
    @GetMapping("/helpdesk/tickets")
    List<Ticket> tickets() {
        return jdbc.query(
                "SELECT id, description FROM support_tickets ORDER BY id DESC",
                (row, n) -> new Ticket(row.getLong("id"), row.getString("description")));
    }

    private interface Answer {
        boolean apply();
    }

    private ResponseEntity<String> answer(String invocationId, String approver, Answer answer, String done) {
        ApprovalRequest request = approvals.pending().stream()
                .filter(pending -> pending.invocation().id().equals(invocationId))
                .findFirst()
                .orElse(null);
        if (request == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("No pending approval " + invocationId);
        }
        // Four eyes: whoever asked the agent cannot approve the agent's call.
        if (approver.equals(request.invocation().principal())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("You cannot answer your own request");
        }
        return answer.apply()
                ? ResponseEntity.ok(done)
                : ResponseEntity.status(HttpStatus.NOT_FOUND).body("No pending approval " + invocationId);
    }
}
