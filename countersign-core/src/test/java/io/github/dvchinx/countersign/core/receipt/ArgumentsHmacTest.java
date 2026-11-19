package io.github.dvchinx.countersign.core.receipt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dvchinx.countersign.core.model.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class ArgumentsHmacTest {

    private static final byte[] KEY = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.US_ASCII);

    @Test
    void matchesHmacSha256OfTheCanonicalJson() {
        // Reference: printf '{"a":1}' | openssl dgst -sha256 -hmac 0123456789abcdef0123456789abcdef
        assertThat(new ArgumentsHmac(KEY).hex(JsonObject.builder().put("a", 1).build()))
                .isEqualTo("f0546246da9e2349100c845a8561214788d75d900f3c086a72fcfa03bb1f42ab");
    }

    @Test
    void equalArgumentsGiveTheSameValueWhateverTheirOrder() {
        ArgumentsHmac hmac = new ArgumentsHmac(KEY);
        JsonObject ab =
                JsonObject.builder().put("amount", 5000).put("to", "ACME").build();
        JsonObject ba =
                JsonObject.builder().put("to", "ACME").put("amount", 5000).build();

        assertThat(hmac.hex(ab)).isEqualTo(hmac.hex(ba)).hasSize(64);
    }

    @Test
    void cannotBeReproducedWithoutTheKey() {
        JsonObject args = JsonObject.builder().put("amount", 5000).build();
        byte[] otherKey = KEY.clone();
        otherKey[0] ^= 1;

        assertThat(new ArgumentsHmac(KEY).hex(args)).isNotEqualTo(new ArgumentsHmac(otherKey).hex(args));
    }

    @Test
    void isNotAffectedByChangesToTheCallersKeyArray() {
        byte[] key = KEY.clone();
        ArgumentsHmac hmac = new ArgumentsHmac(key);
        String before = hmac.hex(JsonObject.empty());

        key[0] ^= 1;

        assertThat(hmac.hex(JsonObject.empty())).isEqualTo(before);
    }

    @Test
    void rejectsShortOrMalformedKeysWithoutEchoingThem() {
        assertThatThrownBy(() -> new ArgumentsHmac(new byte[31])).isInstanceOf(ReceiptException.class);
        assertThatThrownBy(() -> ArgumentsHmac.fromBase64("%%secret%%"))
                .isInstanceOf(ReceiptException.class)
                .message()
                .doesNotContain("secret");
        assertThat(ArgumentsHmac.fromBase64(Base64.getEncoder().encodeToString(KEY))
                        .hex(JsonObject.empty()))
                .isEqualTo(new ArgumentsHmac(KEY).hex(JsonObject.empty()));
        assertThat(new ArgumentsHmac(KEY).toString()).doesNotContain("0123");
    }
}
