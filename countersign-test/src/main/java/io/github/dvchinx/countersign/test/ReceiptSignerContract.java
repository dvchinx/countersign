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
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The tests every {@link ReceiptSigner} must pass. Passing them is the bar for
 * merging a new signer.
 *
 * <p>
 * Extend this class in your signer's test suite and implement {@link #signer()}
 * and {@link #publicKey()}.
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

    @Test
    void signsAHashSoThatItVerifiesWithThePublicKey() throws Exception {
        byte[] hash = sha256("receipt-1");

        byte[] signature = signer().sign(hash);

        assertThat(signature).isNotNull().isNotEmpty();
        assertThat(verifies(hash, signature)).isTrue();
    }

    @Test
    void signatureForOneHashDoesNotVerifyForAnother() throws Exception {
        byte[] signedHash = sha256("receipt-1");
        byte[] otherHash = sha256("receipt-2");

        byte[] signature = signer().sign(signedHash);

        assertThat(verifies(signedHash, signature)).isTrue();
        assertThat(verifies(otherHash, signature)).isFalse();
    }

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
