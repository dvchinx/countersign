package io.github.dvchinx.countersign.test;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dvchinx.countersign.core.spi.ReceiptSigner;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import org.junit.jupiter.api.Test;

/**
 * The tests every {@link ReceiptSigner} must pass. Passing them is the bar for merging a new signer.
 *
 * <p>Extend this class in your signer's test suite and implement {@link #signer()} and {@link #publicKey()}.
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

    private boolean verifies(byte[] hash, byte[] signature) throws GeneralSecurityException {
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(publicKey());
        verifier.update(hash);
        return verifier.verify(signature);
    }

    private static byte[] sha256(String text) throws GeneralSecurityException {
        return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
    }
}
