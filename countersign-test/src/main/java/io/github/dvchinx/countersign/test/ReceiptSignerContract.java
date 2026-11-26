package io.github.dvchinx.countersign.test;

import static io.github.dvchinx.countersign.test.TestReceipts.allow;
import static io.github.dvchinx.countersign.test.TestReceipts.invocation;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.dvchinx.countersign.core.model.ApprovalOutcome;
import io.github.dvchinx.countersign.core.model.Decision;
import io.github.dvchinx.countersign.core.model.EvaluationResult;
import io.github.dvchinx.countersign.core.model.Receipt;
import io.github.dvchinx.countersign.core.receipt.ArgumentsHmac;
import io.github.dvchinx.countersign.core.receipt.ReceiptChain;
import io.github.dvchinx.countersign.core.receipt.ReceiptVerifier;
import io.github.dvchinx.countersign.core.receipt.VerificationResult;
import io.github.dvchinx.countersign.core.spi.ReceiptSigner;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/**
 * The tests every {@link ReceiptSigner} must pass. Passing them is the bar for
 * merging a new signer.
 *
 * <p>
 * Extend this class in your signer's test suite and implement {@link #signer()}
 * and {@link #publicKey()}. The contract checks that:
 *
 * <ol>
 * <li>the signature of a receipt hash is not empty and verifies with the public
 * key;
 * <li>a signature is bound to its hash and does not verify for another one;
 * <li>a chain built with {@link ReceiptChain} passes {@link ReceiptVerifier},
 * so receipts from the signer can be audited;
 * <li>concurrent calls on virtual threads all return valid signatures, because
 * the port must be thread-safe.
 * </ol>
 *
 * <p>
 * Signatures are checked with the JDK's Ed25519 implementation and the key from
 * {@link #publicKey()}, the same check an auditor runs later. A signer backed
 * by a KMS or an HSM passes as long as it returns raw Ed25519 signatures.
 */
public abstract class ReceiptSignerContract {

    /** For subclasses. */
    protected ReceiptSignerContract() {}

    /**
     * Returns the signer under test.
     *
     * @return the signer; it may be the same instance for every test
     */
    protected abstract ReceiptSigner signer();

    /**
     * Returns the Ed25519 public key that matches the signer's private key.
     *
     * @return the public key used to verify the signer's signatures
     */
    protected abstract PublicKey publicKey();

    /**
     * A signer must return a real signature for the receipt's hash. An empty or
     * made-up signature would leave a receipt that nobody can verify, and the audit
     * trail would be worthless.
     */
    @Test
    void signsAHashSoThatItVerifiesWithThePublicKey() throws Exception {
        byte[] hash = sha256("receipt-1");

        byte[] signature = signer().sign(hash);

        assertThat(signature).isNotNull().isNotEmpty();
        assertThat(verifies(hash, signature)).isTrue();
    }

    /**
     * A signature must belong to the hash it was made for. If it also verified for
     * another hash, it could be copied onto an edited receipt. The signature is
     * checked against its own hash first, so a signer that returns garbage cannot
     * pass by accident.
     */
    @Test
    void signatureForOneHashDoesNotVerifyForAnother() throws Exception {
        byte[] signedHash = sha256("receipt-1");
        byte[] otherHash = sha256("receipt-2");

        byte[] signature = signer().sign(signedHash);

        assertThat(verifies(signedHash, signature)).isTrue();
        assertThat(verifies(otherHash, signature)).isFalse();
    }

    /**
     * Receipts are signed by {@link ReceiptChain} and checked by
     * {@link ReceiptVerifier}, not by hand. A signer whose output the verifier
     * rejects, for example because it returns an encoded signature instead of raw
     * bytes, would make every receipt fail the audit. The chain mixes allowed,
     * denied and approved calls, so every kind of receipt is covered.
     */
    @Test
    void signsAChainThatReceiptVerifierAccepts() {
        ReceiptChain chain = newChain();

        List<Receipt> receipts = List.of(
                chain.append(invocation("accounts.balance", "alice", "c1"), allow("read-only")),
                chain.append(
                        invocation("db.query", "alice", "c1"),
                        EvaluationResult.matched("no-sql", Decision.deny("No SQL"))),
                chain.append(
                        invocation("payments.transfer", "alice", "c1"),
                        EvaluationResult.matched("approve", Decision.requireApproval("Large")),
                        ApprovalOutcome.APPROVED,
                        "bob"));

        assertThat(new ReceiptVerifier(publicKey()).verify(receipts)).isEqualTo(new VerificationResult.Valid(3));
    }

    /**
     * Many tool calls are guarded at once through the same signer, so the port must
     * be thread-safe. A signer that shares one {@link Signature} between calls can
     * mix up hashes and still pass the tests above, which make one call at a time.
     * Every hash here is different, so a signature returned for the wrong call is
     * caught. Results are checked on the test thread, because a failed assertion
     * inside a task would not fail the test.
     */
    @Test
    void signsConcurrentlyWithSignaturesThatAllVerify() throws Exception {
        int calls = 200;
        List<byte[]> hashes = new ArrayList<>();
        for (int i = 0; i < calls; i++) {
            hashes.add(sha256("receipt-" + i));
        }

        List<Future<byte[]>> signatures = new ArrayList<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (byte[] hash : hashes) {
                signatures.add(executor.submit(() -> signer().sign(hash)));
            }
        }

        for (int i = 0; i < calls; i++) {
            assertThat(verifies(hashes.get(i), signatures.get(i).get()))
                    .as("signature %d", i)
                    .isTrue();
        }
    }

    private boolean verifies(byte[] hash, byte[] signature) throws GeneralSecurityException {
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(publicKey());
        verifier.update(hash);
        return verifier.verify(signature);
    }

    private static byte[] sha256(String text) throws GeneralSecurityException {
        return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
    }

    private ReceiptChain newChain() {
        return ReceiptChain.start(
                "contract-" + UUID.randomUUID(),
                signer(),
                new ArgumentsHmac(new byte[ArgumentsHmac.MIN_KEY_BYTES]),
                Clock.systemUTC());
    }
}
