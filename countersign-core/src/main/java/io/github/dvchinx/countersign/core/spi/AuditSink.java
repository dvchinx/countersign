package io.github.dvchinx.countersign.core.spi;

import io.github.dvchinx.countersign.core.model.Receipt;

/**
 * Stores receipts, the audit trail of every decision.
 *
 * <p>A port so receipts can go wherever an organization keeps its audit data: a database table, a log pipeline or a
 * SIEM. Implementations should be append-only.
 *
 * <p>Receipts of one stream may arrive out of sequence order: each receipt is chained under a lock, but stored after
 * the lock is released, so that a slow sink never holds up other calls. A sink must therefore keep the
 * {@link Receipt#sequence()} of each receipt, and return a stream in sequence order when it is read back, because the
 * hash chain is verified in that order. Keying receipts on stream id and sequence is enough.
 *
 * <p>If {@link #append} throws, Countersign logs the failure, and with strict auditing denies the call. A sink that
 * buffers or writes asynchronously must report its own failures; Countersign cannot see them.
 */
@FunctionalInterface
public interface AuditSink {

    /**
     * Stores one receipt.
     *
     * @param receipt the receipt, already signed and chained
     */
    void append(Receipt receipt);
}
