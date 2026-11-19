package io.github.dvchinx.countersign.core.receipt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class Ed25519ReceiptSignerTest {

    @Test
    void signaturesVerifyWithTheMatchingPublicKey() throws Exception {
        byte[] hash = CanonicalJson.sha256(new byte[] {1, 2, 3});

        byte[] signature = new Ed25519ReceiptSigner(ReceiptFixtures.KEYS.getPrivate()).sign(hash);

        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(ReceiptFixtures.KEYS.getPublic());
        verifier.update(hash);
        assertThat(verifier.verify(signature)).isTrue();
    }

    @Test
    void readsTheSigningKeyFromBase64Pkcs8() {
        String encoded = Base64.getEncoder()
                .encodeToString(ReceiptFixtures.KEYS.getPrivate().getEncoded());

        Ed25519ReceiptSigner signer = Ed25519ReceiptSigner.fromPkcs8Base64(encoded + "\n");

        assertThat(signer.sign(new byte[32])).hasSize(64);
    }

    @Test
    void rejectsAnInvalidKeyWithoutEchoingIt() {
        String notAKey = Base64.getEncoder().encodeToString("super-secret-bytes".getBytes());

        assertThatThrownBy(() -> Ed25519ReceiptSigner.fromPkcs8Base64(notAKey))
                .isInstanceOf(ReceiptException.class)
                .hasNoCause()
                .message()
                .doesNotContain(notAKey);
        assertThatThrownBy(() -> Ed25519ReceiptSigner.fromPkcs8Base64("%%%")).isInstanceOf(ReceiptException.class);
    }

    @Test
    void rejectsKeysForOtherAlgorithms() throws Exception {
        var ed448 = KeyPairGenerator.getInstance("Ed448").generateKeyPair();
        var ec = KeyPairGenerator.getInstance("EC").generateKeyPair();

        assertThatThrownBy(() -> new Ed25519ReceiptSigner(ed448.getPrivate())).isInstanceOf(ReceiptException.class);
        assertThatThrownBy(() -> new Ed25519ReceiptSigner(ec.getPrivate())).isInstanceOf(ReceiptException.class);
        assertThatThrownBy(() -> new ReceiptVerifier(ed448.getPublic())).isInstanceOf(ReceiptException.class);
    }

    @Test
    void neverPrintsTheKey() {
        assertThat(new Ed25519ReceiptSigner(ReceiptFixtures.KEYS.getPrivate()).toString())
                .contains("hidden");
    }
}
