package io.github.dvchinx.countersign.core.audit;

import io.github.dvchinx.countersign.core.model.Receipt;
import io.github.dvchinx.countersign.core.spi.AuditSink;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Keeps the most recent receipts in memory.
 *
 * <p>Meant for tests, demos and development: receipts are lost on restart, and once {@link #capacity()} is reached
 * the oldest receipt is dropped to make room. A stream with dropped receipts no longer verifies from its start, so
 * use a persistent sink in production.
 *
 * <p>Thread-safe. Receipts are kept in the order they were appended, which is the order of the hash chain.
 */
public final class InMemoryAuditSink implements AuditSink {

    /** The capacity used by {@link #InMemoryAuditSink()}. */
    public static final int DEFAULT_CAPACITY = 10_000;

    private final int capacity;
    private final ArrayDeque<Receipt> receipts;
    private final ReentrantLock lock = new ReentrantLock();

    /** Creates a sink that keeps the last {@value #DEFAULT_CAPACITY} receipts. */
    public InMemoryAuditSink() {
        this(DEFAULT_CAPACITY);
    }

    /**
     * Creates a sink.
     *
     * @param capacity how many receipts to keep; at least 1
     * @throws IllegalArgumentException if {@code capacity} is less than 1
     */
    public InMemoryAuditSink(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be at least 1");
        }
        this.capacity = capacity;
        this.receipts = new ArrayDeque<>(Math.min(capacity, 1024));
    }

    @Override
    public void append(Receipt receipt) {
        Objects.requireNonNull(receipt, "receipt");
        lock.lock();
        try {
            if (receipts.size() == capacity) {
                receipts.removeFirst();
            }
            receipts.addLast(receipt);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns the receipts currently kept, oldest first.
     *
     * @return an immutable snapshot
     */
    public List<Receipt> receipts() {
        lock.lock();
        try {
            return List.copyOf(receipts);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns how many receipts this sink keeps at most.
     *
     * @return the capacity
     */
    public int capacity() {
        return capacity;
    }
}
