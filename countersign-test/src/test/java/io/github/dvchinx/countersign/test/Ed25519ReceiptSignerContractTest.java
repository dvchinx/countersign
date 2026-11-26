package io.github.dvchinx.countersign.test;

import io.github.dvchinx.countersign.core.receipt.Ed25519ReceiptSigner;
import io.github.dvchinx.countersign.core.spi.ReceiptSigner;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;

class Ed25519ReceiptSignerContractTest extends ReceiptSignerContract {

    private static final KeyPair KEYS = generateKeys();

    private final Ed25519ReceiptSigner signer = new Ed25519ReceiptSigner(KEYS.getPrivate());

    @Override
    protected ReceiptSigner signer() {
        return signer;
    }

    @Override
    protected PublicKey publicKey() {
        return KEYS.getPublic();
    }

    private static KeyPair generateKeys() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Ed25519 is not available in this JDK", e);
        }
    }
}
