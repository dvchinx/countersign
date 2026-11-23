package io.github.dvchinx.countersign.core.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dvchinx.countersign.core.model.Decision;
import io.github.dvchinx.countersign.core.model.EvaluationResult;
import io.github.dvchinx.countersign.core.model.Receipt;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class InMemoryAuditSinkTest {

    private static Receipt receipt(long sequence) {
        return receipt("s", sequence);
    }

    private static Receipt receipt(String streamId, long sequence) {
        return new Receipt(
                streamId,
                sequence,
                "inv-" + sequence,
                "tool",
                "hmac",
                Decision.Type.ALLOW,
                null,
                EvaluationResult.Basis.POLICY_MATCHED,
                List.of("p"),
                null,
                null,
                null,
                null,
                Instant.EPOCH,
                "prev",
                "hash",
                "sig");
    }

    @Test
    void keepsReceiptsInTheOrderTheyArrived() {
        InMemoryAuditSink sink = new InMemoryAuditSink();
        sink.append(receipt(1));
        sink.append(receipt(2));

        assertThat(sink.receipts()).extracting(Receipt::sequence).containsExactly(1L, 2L);
        assertThat(sink.capacity()).isEqualTo(InMemoryAuditSink.DEFAULT_CAPACITY);
    }

    @Test
    void returnsOneStreamInSequenceOrderEvenWhenReceiptsArriveOutOfOrder() {
        InMemoryAuditSink sink = new InMemoryAuditSink();
        sink.append(receipt("a", 2));
        sink.append(receipt("b", 1));
        sink.append(receipt("a", 1));
        sink.append(receipt("a", 3));

        assertThat(sink.stream("a")).extracting(Receipt::sequence).containsExactly(1L, 2L, 3L);
        assertThat(sink.stream("b")).extracting(Receipt::sequence).containsExactly(1L);
        assertThat(sink.stream("unknown")).isEmpty();
    }

    @Test
    void dropsTheOldestReceiptWhenFull() {
        InMemoryAuditSink sink = new InMemoryAuditSink(2);
        sink.append(receipt(1));
        sink.append(receipt(2));
        sink.append(receipt(3));

        assertThat(sink.receipts()).extracting(Receipt::sequence).containsExactly(2L, 3L);
    }

    @Test
    void returnsASnapshotThatCallersCannotChange() {
        InMemoryAuditSink sink = new InMemoryAuditSink();
        sink.append(receipt(1));
        List<Receipt> snapshot = sink.receipts();

        sink.append(receipt(2));

        assertThat(snapshot).hasSize(1);
        assertThatThrownBy(() -> snapshot.add(receipt(3))).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void acceptsConcurrentAppends() {
        InMemoryAuditSink sink = new InMemoryAuditSink();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            IntStream.rangeClosed(1, 1000).forEach(i -> executor.submit(() -> sink.append(receipt(i))));
        }

        assertThat(sink.receipts()).hasSize(1000);
    }

    @Test
    void rejectsANonPositiveCapacity() {
        assertThatThrownBy(() -> new InMemoryAuditSink(0)).isInstanceOf(IllegalArgumentException.class);
    }
}
