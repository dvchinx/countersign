package io.github.dvchinx.countersign.autoconfigure;

import io.github.dvchinx.countersign.core.receipt.ArgumentsHmac;
import io.github.dvchinx.countersign.core.receipt.Ed25519ReceiptSigner;
import io.github.dvchinx.countersign.core.receipt.ReceiptException;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.util.Base64;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads the configured receipt keys, or generates throwaway ones so that an application with no key configured still
 * starts (the README quickstart has none). Generated keys are announced with a warning, because receipts signed with
 * them cannot be verified, and argument hashes cannot be matched, after a restart.
 */
final class ReceiptKeys {

    private static final Logger LOG = LoggerFactory.getLogger(ReceiptKeys.class);

    private ReceiptKeys() {}

    static Ed25519ReceiptSigner signer(@Nullable String configuredKey) {
        if (configuredKey != null && !configuredKey.isBlank()) {
            return Ed25519ReceiptSigner.fromPkcs8Base64(configuredKey);
        }
        KeyPair keys = generateEd25519();
        LOG.warn("countersign.receipts.signing-key is not set: receipts are signed with a key generated at startup and"
                + " cannot be verified after a restart. Set it (Ed25519, PKCS#8, Base64) in production.");
        // A public key is not secret; logging it lets a developer verify receipts from this run.
        LOG.info(
                "Countersign receipt verification key for this run (X.509, Base64): {}",
                Base64.getEncoder().encodeToString(keys.getPublic().getEncoded()));
        return new Ed25519ReceiptSigner(keys.getPrivate());
    }

    static ArgumentsHmac argumentsHmac(@Nullable String configuredKey) {
        if (configuredKey != null && !configuredKey.isBlank()) {
            return ArgumentsHmac.fromBase64(configuredKey);
        }
        byte[] key = new byte[ArgumentsHmac.MIN_KEY_BYTES];
        new SecureRandom().nextBytes(key);
        LOG.warn("countersign.receipts.args-hmac-key is not set: argument hashes use a key generated at startup and"
                + " cannot be matched after a restart. Set it (32+ random bytes, Base64) in production.");
        return new ArgumentsHmac(key);
    }

    private static KeyPair generateEd25519() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new ReceiptException("This JVM cannot generate Ed25519 keys", e);
        }
    }
}
