package io.github.dvchinx.countersign.core.receipt;

import io.github.dvchinx.countersign.core.model.Decision;
import io.github.dvchinx.countersign.core.model.EvaluationResult;
import io.github.dvchinx.countersign.core.model.Receipt;
import io.github.dvchinx.countersign.core.model.ToolInvocation;
import io.github.dvchinx.countersign.core.spi.ReceiptSigner;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import org.jspecify.annotations.Nullable;

/**
 * Creates the receipts of one stream, each linked to the one before it and signed.
 *
 * <p>A stream is one unbroken chain, verified on its own. An application typically keeps one chain per running
 * instance, with a stream id that is unique to that instance, or resumes the chain of its last stored receipt with
 * {@link #resume}.
 *
 * <p>Appending is serialized with a lock, because each receipt needs the hash of the previous one. The lock covers
 * only hashing and signing; the arguments are hashed before it is taken. If hashing or signing fails, {@link #append} throws a
 * {@link ReceiptException} and the chain does not advance, so the next receipt still links to the last good one.
 *
 * <p>Thread-safe. Uses a {@link ReentrantLock} rather than {@code synchronized} so that virtual threads waiting
 * on it are not pinned.
 */
public final class ReceiptChain {

    private static final HexFormat HEX = HexFormat.of();

    private final String streamId;
    private final ReceiptSigner signer;
    private final ArgumentsHmac argumentsHmac;
    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();

    private long lastSequence;
    private String lastHash;

    private ReceiptChain(
            String streamId,
            ReceiptSigner signer,
            ArgumentsHmac argumentsHmac,
            Clock clock,
            long lastSequence,
            String lastHash) {
        this.streamId = streamId;
        this.signer = Objects.requireNonNull(signer, "signer");
        this.argumentsHmac = Objects.requireNonNull(argumentsHmac, "argumentsHmac");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.lastSequence = lastSequence;
        this.lastHash = lastHash;
    }

    /**
     * Starts a new stream. Its first receipt has sequence 1 and links to {@link Receipt#GENESIS_HASH}.
     *
     * @param streamId an id no other stream uses, for example a UUID per application instance
     * @param signer signs each receipt's hash
     * @param argumentsHmac computes the keyed hash of the arguments stored in each receipt
     * @param clock the source of receipt timestamps
     * @return the chain
     * @throws IllegalArgumentException if {@code streamId} is blank
     */
    public static ReceiptChain start(String streamId, ReceiptSigner signer, ArgumentsHmac argumentsHmac, Clock clock) {
        Objects.requireNonNull(streamId, "streamId");
        if (streamId.isBlank()) {
            throw new IllegalArgumentException("streamId must not be blank");
        }
        return new ReceiptChain(streamId, signer, argumentsHmac, clock, 0, Receipt.GENESIS_HASH);
    }

    /**
     * Continues an existing stream after its last stored receipt, for example after a restart.
     *
     * <p>The caller is responsible for passing the real last receipt of the stream. Verify the stored stream first
     * if it may have been changed; otherwise the new receipts extend a chain that was already broken.
     *
     * @param last the last receipt of the stream
     * @param signer signs each receipt's hash
     * @param argumentsHmac computes the keyed hash of the arguments; use the same key as the stream's earlier
     *     receipts, or argument hashes in one stream stop being comparable
     * @param clock the source of receipt timestamps
     * @return the chain
     */
    public static ReceiptChain resume(Receipt last, ReceiptSigner signer, ArgumentsHmac argumentsHmac, Clock clock) {
        Objects.requireNonNull(last, "last");
        return new ReceiptChain(last.streamId(), signer, argumentsHmac, clock, last.sequence(), last.hash());
    }

    /**
     * Creates, signs and links the receipt for one evaluated invocation.
     *
     * @param invocation the invocation that was evaluated
     * @param result what the policy engine decided
     * @return the signed receipt
     * @throws ReceiptException if hashing or signing fails; the chain does not advance and the call must be denied
     */
    public Receipt append(ToolInvocation invocation, EvaluationResult result) {
        Objects.requireNonNull(invocation, "invocation");
        Objects.requireNonNull(result, "result");
        String argsHmac = argumentsHmac.hex(invocation.arguments());
        List<String> matchedPolicies = result.policyId() == null ? List.of() : List.of(result.policyId());

        lock.lock();
        try {
            ReceiptBody body = new ReceiptBody(
                    streamId,
                    lastSequence + 1,
                    invocation.id(),
                    invocation.toolName(),
                    argsHmac,
                    result.decision().type(),
                    reasonOf(result.decision()),
                    result.basis(),
                    matchedPolicies,
                    invocation.principal(),
                    invocation.conversationId(),
                    // Millisecond precision survives a round trip through any database, so stored receipts still
                    // verify.
                    clock.instant().truncatedTo(ChronoUnit.MILLIS),
                    lastHash);
            byte[] hash = body.sha256();
            String signature = Base64.getEncoder().encodeToString(sign(hash));
            Receipt receipt = body.seal(HEX.formatHex(hash), signature);
            lastSequence = receipt.sequence();
            lastHash = receipt.hash();
            return receipt;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns the id of this chain's stream.
     *
     * @return the stream id
     */
    public String streamId() {
        return streamId;
    }

    private byte[] sign(byte[] hash) {
        byte[] signature;
        try {
            signature = signer.sign(hash.clone());
        } catch (RuntimeException e) {
            throw new ReceiptException("Signing the receipt failed", e);
        }
        if (signature == null || signature.length == 0) {
            throw new ReceiptException("The receipt signer returned no signature");
        }
        return signature;
    }

    private static @Nullable String reasonOf(Decision decision) {
        return switch (decision) {
            case Decision.Allow allow -> null;
            case Decision.Deny deny -> deny.reason();
            case Decision.RequireApproval approval -> approval.reason();
        };
    }
}
