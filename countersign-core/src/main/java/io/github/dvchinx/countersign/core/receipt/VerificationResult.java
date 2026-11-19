package io.github.dvchinx.countersign.core.receipt;

import java.util.Objects;

/**
 * The outcome of verifying a stream of receipts with {@link ReceiptVerifier}.
 *
 * <p>Sealed, so that callers handle both outcomes explicitly instead of checking a boolean they might forget.
 */
public sealed interface VerificationResult permits VerificationResult.Valid, VerificationResult.Invalid {

    /**
     * Tells whether every receipt checked out.
     *
     * @return {@code true} for {@link Valid}
     */
    default boolean isValid() {
        return this instanceof Valid;
    }

    /** What is wrong with a receipt. */
    enum Problem {
        /** The receipt belongs to another stream than the first one checked. */
        STREAM_MISMATCH,
        /** The sequence is not the previous one plus one: a receipt was deleted, inserted or reordered. */
        SEQUENCE_GAP,
        /** The previous hash does not match the previous receipt: the chain was cut or spliced. */
        CHAIN_BROKEN,
        /** The stored hash does not match the receipt's content: a field was edited. */
        HASH_MISMATCH,
        /** The signature does not match the hash for the given public key. */
        BAD_SIGNATURE
    }

    /**
     * Every receipt checked out.
     *
     * @param receiptsChecked how many receipts were verified
     */
    record Valid(int receiptsChecked) implements VerificationResult {}

    /**
     * The first receipt that did not check out. Receipts after it were not checked.
     *
     * @param index the position of the receipt in the list that was verified
     * @param sequence the sequence stored in that receipt
     * @param problem what is wrong
     */
    record Invalid(int index, long sequence, Problem problem) implements VerificationResult {

        /**
         * Creates the result.
         *
         * @param index the position of the receipt in the list that was verified
         * @param sequence the sequence stored in that receipt
         * @param problem what is wrong
         */
        public Invalid {
            Objects.requireNonNull(problem, "problem");
        }
    }
}
