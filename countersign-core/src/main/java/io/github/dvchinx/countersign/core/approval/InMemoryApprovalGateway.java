package io.github.dvchinx.countersign.core.approval;

import io.github.dvchinx.countersign.core.model.ApprovalDecision;
import io.github.dvchinx.countersign.core.model.ApprovalRequest;
import io.github.dvchinx.countersign.core.spi.ApprovalGateway;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.jspecify.annotations.Nullable;

/**
 * Holds approval requests in memory until the application answers them.
 *
 * <p>The building block for an approval screen or endpoint inside the same application: list {@link #pending()},
 * then call {@link #approve} or {@link #reject} with the authenticated identity of the person who answered. A request
 * leaves the list as soon as it is answered, times out, or is withdrawn by Countersign.
 *
 * <p>Requests are lost on restart, and only the instance that received a request can answer it. Thread-safe.
 */
public final class InMemoryApprovalGateway implements ApprovalGateway {

    private final ConcurrentMap<String, Pending> pending = new ConcurrentHashMap<>();

    private record Pending(ApprovalRequest request, CompletableFuture<ApprovalDecision> answer) {}

    @Override
    public CompletionStage<ApprovalDecision> request(ApprovalRequest request) {
        Objects.requireNonNull(request, "request");
        String id = request.invocation().id();
        CompletableFuture<ApprovalDecision> answer = new CompletableFuture<>();
        Pending entry = new Pending(request, answer);
        if (pending.putIfAbsent(id, entry) != null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("An approval request for invocation " + id + " is already pending"));
        }
        // Answered, timed out or cancelled by Countersign: in every case the request is no longer pending.
        answer.whenComplete((decision, failure) -> pending.remove(id, entry));
        return answer;
    }

    /**
     * Returns the requests waiting for an answer, the closest deadline first.
     *
     * @return an immutable snapshot
     */
    public List<ApprovalRequest> pending() {
        return pending.values().stream()
                .map(Pending::request)
                .sorted(Comparator.comparing(ApprovalRequest::deadline))
                .toList();
    }

    /**
     * Approves a pending call.
     *
     * @param invocationId the id of the call, from {@link #pending()}
     * @param approver the authenticated identity of the person approving
     * @return {@code true} if the call was pending; {@code false} if it was already answered, timed out or unknown
     */
    public boolean approve(String invocationId, String approver) {
        return answer(invocationId, ApprovalDecision.approved(approver));
    }

    /**
     * Rejects a pending call.
     *
     * @param invocationId the id of the call, from {@link #pending()}
     * @param approver the authenticated identity of the person rejecting
     * @param reason why, shown to the model; optional
     * @return {@code true} if the call was pending; {@code false} if it was already answered, timed out or unknown
     */
    public boolean reject(String invocationId, String approver, @Nullable String reason) {
        return answer(invocationId, ApprovalDecision.rejected(approver, reason));
    }

    private boolean answer(String invocationId, ApprovalDecision decision) {
        Objects.requireNonNull(invocationId, "invocationId");
        Pending entry = pending.get(invocationId);
        return entry != null && entry.answer().complete(decision);
    }
}
