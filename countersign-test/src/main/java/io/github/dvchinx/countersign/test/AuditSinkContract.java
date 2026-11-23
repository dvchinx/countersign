package io.github.dvchinx.countersign.test;

import static io.github.dvchinx.countersign.test.TestReceipts.allow;
import static io.github.dvchinx.countersign.test.TestReceipts.invocation;
import static io.github.dvchinx.countersign.test.TestReceipts.newChain;
import static io.github.dvchinx.countersign.test.TestReceipts.verifier;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.dvchinx.countersign.core.model.ApprovalOutcome;
import io.github.dvchinx.countersign.core.model.Decision;
import io.github.dvchinx.countersign.core.model.EvaluationResult;
import io.github.dvchinx.countersign.core.model.Receipt;
import io.github.dvchinx.countersign.core.receipt.ReceiptChain;
import io.github.dvchinx.countersign.core.receipt.VerificationResult;
import io.github.dvchinx.countersign.core.spi.AuditSink;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * The tests every {@link AuditSink} must pass. Passing them is the bar for merging a new sink.
 *
 * <p>Extend this class in your sink's test suite and implement {@link #sink()} and {@link #storedStream(String)}. The
 * contract checks that a stored stream reads back unchanged, field for field, in sequence order, and still verifies
 * with {@code ReceiptVerifier}, including when receipts arrive out of order from concurrent calls.
 *
 * <p>Every test uses its own random stream ids, so the sink may be shared between tests, for example one database for
 * the whole class.
 */
public abstract class AuditSinkContract {

    /** For subclasses. */
    protected AuditSinkContract() {}

    /**
     * Returns the sink under test.
     *
     * @return the sink; it may be the same instance for every test
     */
    protected abstract AuditSink sink();

    /**
     * Reads back what the sink stored for one stream, the way an auditor would before verifying it.
     *
     * @param streamId the stream
     * @return the stream's receipts in sequence order; empty if none were stored
     */
    protected abstract List<Receipt> storedStream(String streamId);

    @Test
    void storesEveryFieldOfAReceipt() {
        ReceiptChain chain = newChain();
        Receipt receipt = chain.append(
                invocation("payments.transfer", "alice@example.com", "conv-ñ-7"),
                EvaluationResult.matched(
                        "transfers-need-approval", Decision.requireApproval("Moves real money: más de 1.000 € 💸")),
                ApprovalOutcome.REJECTED,
                "bob@example.com");

        sink().append(receipt);

        assertThat(storedStream(chain.streamId())).containsExactly(receipt);
    }

    @Test
    void storesAReceiptWithoutOptionalFields() {
        ReceiptChain chain = newChain();
        Receipt receipt = chain.append(
                invocation("accounts.balance", null, null), EvaluationResult.defaultDecision(Decision.allow()));

        sink().append(receipt);

        assertThat(storedStream(chain.streamId())).containsExactly(receipt);
        assertThat(receipt.matchedPolicies()).isEmpty();
    }

    @Test
    void returnsAStoredStreamThatStillVerifies() {
        ReceiptChain chain = newChain();
        List<Receipt> appended = new ArrayList<>();
        appended.add(chain.append(invocation("accounts.balance", "alice", "c1"), allow("read-only")));
        appended.add(chain.append(
                invocation("db.query", "alice", "c1"), EvaluationResult.matched("no-sql", Decision.deny("No SQL"))));
        appended.add(chain.append(
                invocation("payments.transfer", "alice", "c1"),
                EvaluationResult.matched("approve", Decision.requireApproval("Large")),
                ApprovalOutcome.TIMED_OUT,
                null));
        appended.forEach(sink()::append);

        List<Receipt> stored = storedStream(chain.streamId());

        assertThat(stored).containsExactlyElementsOf(appended);
        assertThat(verifier().verify(stored)).isEqualTo(new VerificationResult.Valid(3));
    }

    @Test
    void returnsAStreamInSequenceOrderWhenReceiptsArriveOutOfOrder() {
        ReceiptChain chain = newChain();
        List<Receipt> appended = IntStream.range(0, 5)
                .mapToObj(i -> chain.append(invocation("accounts.balance", "alice", "c1"), allow("read-only")))
                .toList();
        List<Receipt> shuffled = new ArrayList<>(appended);
        Collections.reverse(shuffled);
        shuffled.forEach(sink()::append);

        assertThat(storedStream(chain.streamId())).containsExactlyElementsOf(appended);
    }

    @Test
    void keepsStreamsApart() {
        ReceiptChain first = newChain();
        ReceiptChain second = newChain();
        Receipt a1 = first.append(invocation("accounts.balance", "alice", "c1"), allow("read-only"));
        Receipt b1 = second.append(invocation("accounts.balance", "bob", "c2"), allow("read-only"));
        Receipt a2 = first.append(invocation("accounts.balance", "alice", "c1"), allow("read-only"));
        List.of(a1, b1, a2).forEach(sink()::append);

        assertThat(storedStream(first.streamId())).containsExactly(a1, a2);
        assertThat(storedStream(second.streamId())).containsExactly(b1);
        assertThat(storedStream("no-such-stream-" + UUID.randomUUID())).isEmpty();
    }

    @Test
    void storesEveryReceiptFromConcurrentCalls() {
        ReceiptChain chain = newChain();
        AuditSink sink = sink();
        int calls = 200;
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            // Like ToolCallGuard: chain under the chain's lock, then store without it.
            IntStream.range(0, calls)
                    .forEach(i -> executor.submit(() -> sink.append(
                            chain.append(invocation("accounts.balance", "alice", "c" + i), allow("read-only")))));
        }

        List<Receipt> stored = storedStream(chain.streamId());

        assertThat(stored).hasSize(calls);
        assertThat(verifier().verify(stored)).isEqualTo(new VerificationResult.Valid(calls));
    }
}
