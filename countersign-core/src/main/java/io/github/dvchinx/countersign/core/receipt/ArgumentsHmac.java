package io.github.dvchinx.countersign.core.receipt;

import io.github.dvchinx.countersign.core.model.JsonObject;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Computes the keyed hash of a call's arguments that receipts store instead of the arguments themselves.
 *
 * <p>A plain SHA-256 can be reversed by guessing when arguments have few likely values: hash every amount from 1 to
 * 10,000 with a known account and compare. HMAC-SHA256 with a secret key prevents that for anyone who has the
 * receipts but not the key, while whoever holds the key can still check that a given set of arguments matches a
 * receipt (ADR 0004). Keep the key out of the audit store.
 *
 * <p>The input is the UTF-8 of the arguments' {@linkplain CanonicalJson canonical JSON}, so equal arguments always
 * give the same value. Thread-safe; the key is never printed.
 */
public final class ArgumentsHmac {

    /** The minimum key length in bytes: the output size of SHA-256, as RFC 2104 recommends. */
    public static final int MIN_KEY_BYTES = 32;

    private static final String ALGORITHM = "HmacSHA256";
    private static final HexFormat HEX = HexFormat.of();

    private final SecretKeySpec key;

    /**
     * Creates the hasher.
     *
     * @param key the secret key; at least {@value #MIN_KEY_BYTES} bytes. The array is copied.
     * @throws ReceiptException if the key is too short
     */
    public ArgumentsHmac(byte[] key) {
        Objects.requireNonNull(key, "key");
        if (key.length < MIN_KEY_BYTES) {
            throw new ReceiptException("Arguments HMAC key must be at least " + MIN_KEY_BYTES + " bytes");
        }
        byte[] copy = key.clone();
        this.key = new SecretKeySpec(copy, ALGORITHM);
        Arrays.fill(copy, (byte) 0);
    }

    /**
     * Creates the hasher from a Base64-encoded key, the format of {@code countersign.receipts.args-hmac-key}.
     *
     * @param base64Key the key, Base64 encoded
     * @return the hasher
     * @throws ReceiptException if the text is not Base64 or the key is too short
     */
    public static ArgumentsHmac fromBase64(String base64Key) {
        Objects.requireNonNull(base64Key, "base64Key");
        byte[] key;
        try {
            key = Base64.getDecoder().decode(base64Key.strip());
        } catch (IllegalArgumentException e) {
            throw new ReceiptException("Arguments HMAC key is not valid Base64");
        }
        try {
            return new ArgumentsHmac(key);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    /**
     * Computes the keyed hash of a call's arguments.
     *
     * @param arguments the arguments
     * @return HMAC-SHA256 of their canonical JSON, as 64 lowercase hex characters
     * @throws ReceiptException if the platform cannot compute HMAC-SHA256
     */
    public String hex(JsonObject arguments) {
        Objects.requireNonNull(arguments, "arguments");
        try {
            // Mac instances are not thread-safe; a new one per call is cheap next to the hashing itself.
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return HEX.formatHex(mac.doFinal(CanonicalJson.write(arguments).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new ReceiptException("HMAC-SHA256 is not available", e);
        }
    }

    @Override
    public String toString() {
        return "ArgumentsHmac[key=hidden]";
    }
}
