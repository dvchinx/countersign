package io.github.dvchinx.countersign.core.receipt;

import io.github.dvchinx.countersign.core.spi.ReceiptSigner;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.EdECPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Objects;

/**
 * Signs receipts with an Ed25519 private key held in memory, using only {@code java.security}.
 *
 * <p>Ed25519 gives small signatures, fast signing and no parameters to get wrong, and the JDK has supported it since
 * Java 15, so no crypto dependency is needed. Exception messages never include key material.
 */
public final class Ed25519ReceiptSigner implements ReceiptSigner {

    private static final String ALGORITHM = "Ed25519";

    private final PrivateKey privateKey;

    /**
     * Creates a signer.
     *
     * @param privateKey an Ed25519 private key
     * @throws ReceiptException if the key is not an Ed25519 key
     */
    public Ed25519ReceiptSigner(PrivateKey privateKey) {
        Objects.requireNonNull(privateKey, "privateKey");
        if (!(privateKey instanceof EdECPrivateKey edKey)
                || !ALGORITHM.equals(edKey.getParams().getName())) {
            throw new ReceiptException("Receipt signing key must be an Ed25519 private key");
        }
        this.privateKey = privateKey;
    }

    /**
     * Creates a signer from a Base64-encoded PKCS#8 private key, the format of
     * {@code countersign.receipts.signing-key}.
     *
     * @param base64Pkcs8 the private key, PKCS#8 DER encoded as Base64
     * @return the signer
     * @throws ReceiptException if the text is not a valid Ed25519 PKCS#8 key
     */
    public static Ed25519ReceiptSigner fromPkcs8Base64(String base64Pkcs8) {
        Objects.requireNonNull(base64Pkcs8, "base64Pkcs8");
        try {
            byte[] der = Base64.getDecoder().decode(base64Pkcs8.strip());
            return new Ed25519ReceiptSigner(
                    KeyFactory.getInstance(ALGORITHM).generatePrivate(new PKCS8EncodedKeySpec(der)));
        } catch (IllegalArgumentException | GeneralSecurityException e) {
            // The cause's message is not included: it may describe the key bytes.
            throw new ReceiptException("Receipt signing key is not a valid Base64 PKCS#8 Ed25519 private key");
        }
    }

    @Override
    public byte[] sign(byte[] hash) {
        try {
            // Signature objects are not thread-safe, and creating one is cheap next to the signing itself.
            Signature signature = Signature.getInstance(ALGORITHM);
            signature.initSign(privateKey);
            signature.update(hash);
            return signature.sign();
        } catch (GeneralSecurityException e) {
            throw new ReceiptException("Ed25519 signing failed", e);
        }
    }

    @Override
    public String toString() {
        return "Ed25519ReceiptSigner[key=hidden]";
    }
}
