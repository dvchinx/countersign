package io.github.dvchinx.countersign.core.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The signed, hash-chained record of one decision: the unit of audit.
 *
 * <p>A receipt answers "what did the agent try to do, and who allowed it?" without storing the arguments
 * themselves: it keeps a keyed hash of them ({@link #argsHmac()}, ADR 0004). Each receipt includes the hash of the one before it in
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
 * @param argsHmac HMAC-SHA256 of the canonical JSON of the arguments, lowercase hex; see {@code ArgumentsHmac}
 * @param decision the kind of decision
 * @param reason the decision's reason, or {@code null} for {@link Decision.Type#ALLOW}
 * @param basis why the decision was reached
 * @param matchedPolicies the ids of the policies that produced the decision; empty when the default applied
 * @param redactions the ids of the redaction rules that apply to the call, in the order they are applied; empty when
 *     none applies or the call does not run (ADR 0015). Added after 0.1.0, so it is left out of the hashed JSON when
 *     empty, and receipts from 0.1.0 keep verifying
 * @param approval what happened to the approval, for {@link Decision.Type#REQUIRE_APPROVAL}; otherwise {@code null}
 * @param approver who approved or rejected the call, or {@code null} if nobody answered
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
        String argsHmac,
        Decision.Type decision,
        @Nullable String reason,
        EvaluationResult.Basis basis,
        List<String> matchedPolicies,
        List<String> redactions,
        @Nullable ApprovalOutcome approval,
        @Nullable String approver,
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
        Objects.requireNonNull(argsHmac, "argsHmac");
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(basis, "basis");
        matchedPolicies = List.copyOf(matchedPolicies);
        redactions = List.copyOf(redactions);
        Objects.requireNonNull(timestamp, "timestamp");
        Objects.requireNonNull(previousHash, "previousHash");
        Objects.requireNonNull(hash, "hash");
        Objects.requireNonNull(signature, "signature");
    }

    /**
     * Creates a receipt with no redactions, as 0.1.0 did. Kept so that code reading receipts back from storage, such
     * as an {@code AuditSink}, still compiles.
     *
     * @param streamId the chain this receipt belongs to
     * @param sequence the position in the stream, starting at 1
     * @param invocationId the id of the invocation this receipt records
     * @param tool the tool's name
     * @param argsHmac HMAC-SHA256 of the canonical JSON of the arguments, lowercase hex
     * @param decision the kind of decision
     * @param reason the decision's reason, or {@code null}
     * @param basis why the decision was reached
     * @param matchedPolicies the ids of the policies that produced the decision
     * @param approval what happened to the approval, or {@code null}
     * @param approver who approved or rejected the call, or {@code null}
     * @param principal who the agent acted for, or {@code null}
     * @param conversationId the conversation, or {@code null}
     * @param timestamp when the receipt was created
     * @param previousHash the hash of the previous receipt in the stream
     * @param hash SHA-256 of the canonical JSON of the receipt, lowercase hex
     * @param signature signature over the raw bytes of the hash, Base64
     * @throws IllegalArgumentException if {@code sequence} is less than 1
     */
    public Receipt(
            String streamId,
            long sequence,
            String invocationId,
            String tool,
            String argsHmac,
            Decision.Type decision,
            @Nullable String reason,
            EvaluationResult.Basis basis,
            List<String> matchedPolicies,
            @Nullable ApprovalOutcome approval,
            @Nullable String approver,
            @Nullable String principal,
            @Nullable String conversationId,
            Instant timestamp,
            String previousHash,
            String hash,
            String signature) {
        this(
                streamId,
                sequence,
                invocationId,
                tool,
                argsHmac,
                decision,
                reason,
                basis,
                matchedPolicies,
                List.of(),
                approval,
                approver,
                principal,
                conversationId,
                timestamp,
                previousHash,
                hash,
                signature);
    }
}
