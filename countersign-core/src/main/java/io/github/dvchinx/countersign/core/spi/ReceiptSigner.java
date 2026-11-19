package io.github.dvchinx.countersign.core.spi;

/**
 * Signs receipt hashes.
 *
 * <p>A port so that the private key can live somewhere other than the JVM, such as a KMS or an HSM. The default
 * implementation, {@code Ed25519ReceiptSigner}, keeps an Ed25519 key in memory and uses only {@code java.security}.
 *
 * <p>Implementations must be thread-safe. If signing fails they must throw, never return an empty or partial
 * signature: the call is then denied (fail closed), and the chain does not advance.
 */
@FunctionalInterface
public interface ReceiptSigner {

    /**
     * Signs a receipt hash.
     *
     * @param hash the raw 32 bytes of the receipt's SHA-256 hash
     * @return the signature bytes; never {@code null} or empty
     */
    byte[] sign(byte[] hash);
}
