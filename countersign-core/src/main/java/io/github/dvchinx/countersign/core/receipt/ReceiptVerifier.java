package io.github.dvchinx.countersign.core.receipt;

import io.github.dvchinx.countersign.core.model.Receipt;
import io.github.dvchinx.countersign.core.receipt.VerificationResult.Invalid;
import io.github.dvchinx.countersign.core.receipt.VerificationResult.Problem;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.EdECPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Checks that a stored stream of receipts is complete and unchanged.
 *
 * <p>For each receipt, in order, it checks that:
 *
 * <ol>
 *   <li>it belongs to the same stream as the first one;
 *   <li>its sequence follows the previous one, starting at 1;
 *   <li>its previous hash is the previous receipt's hash, or {@link Receipt#GENESIS_HASH} for the first;
 *   <li>its hash matches its content;
 *   <li>its signature is valid for that hash and the given Ed25519 public key.
 * </ol>
 *
 * <p>Together these detect any edited, deleted, inserted or reordered receipt. Deleting receipts from the end of a
 * stream leaves a shorter stream that is still valid; detecting that needs an outside reference, such as the last
 * sequence number kept somewhere the writer of the receipts cannot change.
 *
 * <p>Thread-safe.
 */
public final class ReceiptVerifier {

    private static final String ALGORITHM = "Ed25519";
    private static final HexFormat HEX = HexFormat.of();

    private final PublicKey publicKey;

    /**
     * Creates a verifier.
     *
     * @param publicKey the Ed25519 public key matching the signing key
     * @throws ReceiptException if the key is not an Ed25519 key
     */
    public ReceiptVerifier(PublicKey publicKey) {
        Objects.requireNonNull(publicKey, "publicKey");
        if (!(publicKey instanceof EdECPublicKey edKey)
                || !ALGORITHM.equals(edKey.getParams().getName())) {
            throw new ReceiptException("Receipt verification key must be an Ed25519 public key");
        }
        this.publicKey = publicKey;
    }

    /**
     * Creates a verifier from a Base64-encoded X.509 (SubjectPublicKeyInfo) public key.
     *
     * @param base64X509 the public key, X.509 DER encoded as Base64
     * @return the verifier
     * @throws ReceiptException if the text is not a valid Ed25519 public key
     */
    public static ReceiptVerifier fromX509Base64(String base64X509) {
        Objects.requireNonNull(base64X509, "base64X509");
        try {
            byte[] der = Base64.getDecoder().decode(base64X509.strip());
            return new ReceiptVerifier(KeyFactory.getInstance(ALGORITHM).generatePublic(new X509EncodedKeySpec(der)));
        } catch (IllegalArgumentException | GeneralSecurityException e) {
            throw new ReceiptException("Receipt verification key is not a valid Base64 X.509 Ed25519 public key", e);
        }
    }

    /**
     * Verifies one whole stream, from its first receipt, in sequence order.
     *
     * @param stream the receipts of one stream, in the order they were created
     * @return {@link VerificationResult.Valid}, or the first receipt that does not check out
     */
    public VerificationResult verify(List<Receipt> stream) {
        Objects.requireNonNull(stream, "stream");
        if (stream.isEmpty()) {
            return new VerificationResult.Valid(0);
        }
        String streamId = stream.getFirst().streamId();
        String expectedPrevious = Receipt.GENESIS_HASH;
        for (int i = 0; i < stream.size(); i++) {
            Receipt receipt = stream.get(i);
            Problem problem = check(receipt, streamId, i + 1L, expectedPrevious);
            if (problem != null) {
                return new Invalid(i, receipt.sequence(), problem);
            }
            expectedPrevious = receipt.hash();
        }
        return new VerificationResult.Valid(stream.size());
    }

    private @Nullable Problem check(Receipt receipt, String streamId, long expectedSequence, String expectedPrevious) {
        if (!receipt.streamId().equals(streamId)) {
            return Problem.STREAM_MISMATCH;
        }
        if (receipt.sequence() != expectedSequence) {
            return Problem.SEQUENCE_GAP;
        }
        if (!receipt.previousHash().equals(expectedPrevious)) {
            return Problem.CHAIN_BROKEN;
        }
        byte[] hash = ReceiptBody.of(receipt).sha256();
        // Compare the exact text: parsing would accept uppercase hex, so a changed stored hash could go unnoticed.
        if (!HEX.formatHex(hash).equals(receipt.hash())) {
            return Problem.HASH_MISMATCH;
        }
        if (!signatureMatches(hash, receipt.signature())) {
            return Problem.BAD_SIGNATURE;
        }
        return null;
    }

    private boolean signatureMatches(byte[] hash, String signatureBase64) {
        try {
            Signature signature = Signature.getInstance(ALGORITHM);
            signature.initVerify(publicKey);
            signature.update(hash);
            return signature.verify(Base64.getDecoder().decode(signatureBase64));
        } catch (IllegalArgumentException | GeneralSecurityException e) {
            return false;
        }
    }
}
