package io.github.dvchinx.countersign.examples.banking;

import io.github.dvchinx.countersign.core.receipt.ArgumentsHmac;
import io.github.dvchinx.countersign.core.receipt.Ed25519ReceiptSigner;
import io.github.dvchinx.countersign.core.receipt.ReceiptVerifier;
import io.github.dvchinx.countersign.core.spi.ReceiptSigner;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * DEMO ONLY: receipt keys generated at startup, so the demo needs no secrets.
 *
 * <p>Defining these beans replaces the starter's own generated keys, and keeps the public key at hand for
 * {@code GET /receipts/verify}. In production, set {@code countersign.receipts.signing-key} and
 * {@code countersign.receipts.args-hmac-key} from your secret store instead, and verify with the matching public key.
 */
@Configuration(proxyBeanMethods = false)
class DemoKeys {

    private static final Logger LOG = LoggerFactory.getLogger(DemoKeys.class);

    private final KeyPair keys = generate();

    private static KeyPair generate() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Ed25519 is not available in this JDK", e);
        }
    }

    @Bean
    ReceiptSigner demoReceiptSigner() {
        LOG.warn("DEMO ONLY: receipts are signed with a key generated at startup; use a real key in production");
        return new Ed25519ReceiptSigner(keys.getPrivate());
    }

    @Bean
    ArgumentsHmac demoArgumentsHmac() {
        byte[] key = new byte[ArgumentsHmac.MIN_KEY_BYTES];
        new SecureRandom().nextBytes(key);
        return new ArgumentsHmac(key);
    }

    @Bean
    ReceiptVerifier receiptVerifier() {
        return new ReceiptVerifier(keys.getPublic());
    }
}
