package io.github.dvchinx.countersign.core.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The signed, hash-chained record of one decision: the unit of audit.
 *
 * <p>A receipt answers "what did the agent try to do, and who allowed it?" without storing the arguments
 * themselves: it keeps their SHA-256 ({@link #argsSha256()}). Each receipt includes the hash of the one before it in
 * the same stream ({@link #previousHash()}), and its own {@link #hash()} is signed with Ed25519, so editing,
 * deleting, inserting or reordering a stored receipt is detected on verification. The exact format is described in
 * ADR 0003.
 *
 * <p>Receipts are created by {@code ReceiptChain} and checked by {@code ReceiptVerifier}; build one by hand only
 * when reading it back from storage.
 *
 * @param streamId the chain this receipt belongs to; each stream is verified on its own
 * @param sequence the position in the stream, starting at 1 with no gaps
 * @param invocationId the id of the invocation this receipt records
 * @param tool the tool's name
 * @param argsSha256 SHA-256 of the canonical JSON of the arguments, lowercase hex
 * @param decision the kind of decision
 * @param reason the decision's reason, or {@code null} for {@link Decision.Type#ALLOW}
 * @param basis why the decision was reached
 * @param matchedPolicies the ids of the policies that produced the decision; empty when the default applied
 * @param principal who the agent acted for, or {@code null} if unknown
 * @param conversationId the conversation, or {@code null} if unknown
 * @param timestamp when the receipt was created, to the millisecond
 * @param previousHash the {@link #hash()} of the previous receipt in the stream, or {@link #GENESIS_HASH} for the
 *     first one
 * @param hash SHA-256 of the canonical JSON of every field above, lowercase hex
 * @param signature signature over the raw bytes of {@link #hash()}, Base64
 */
public record Receipt(
        String streamId,
        long sequence,
        String invocationId,
        String tool,
        String argsSha256,
        Decision.Type decision,
        @Nullable String reason,
        EvaluationResult.Basis basis,
        List<String> matchedPolicies,
        @Nullable String principal,
        @Nullable String conversationId,
        Instant timestamp,
        String previousHash,
        String hash,
        String signature) {

    /** The {@link #previousHash()} of the first receipt in a stream: 64 zeros. */
    public static final String GENESIS_HASH = "0".repeat(64);

    /**
     * Creates a receipt.
     *
     * @throws IllegalArgumentException if {@code sequence} is less than 1
     */
    public Receipt {
        Objects.requireNonNull(streamId, "streamId");
        if (sequence < 1) {
            throw new IllegalArgumentException("sequence must be at least 1");
        }
        Objects.requireNonNull(invocationId, "invocationId");
        Objects.requireNonNull(tool, "tool");
        Objects.requireNonNull(argsSha256, "argsSha256");
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(basis, "basis");
        matchedPolicies = List.copyOf(matchedPolicies);
        Objects.requireNonNull(timestamp, "timestamp");
        Objects.requireNonNull(previousHash, "previousHash");
        Objects.requireNonNull(hash, "hash");
        Objects.requireNonNull(signature, "signature");
    }
}
