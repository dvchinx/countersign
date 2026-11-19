package io.github.dvchinx.countersign.core.receipt;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dvchinx.countersign.core.model.JsonArray;
import io.github.dvchinx.countersign.core.model.JsonNumber;
import io.github.dvchinx.countersign.core.model.JsonObject;
import io.github.dvchinx.countersign.core.model.JsonString;
import io.github.dvchinx.countersign.core.model.JsonValue;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CanonicalJsonTest {

    @Test
    void sortsMembersAndRemovesWhitespace() {
        JsonObject value = JsonObject.builder()
                .put("to", "ACME")
                .put("amount", 5000)
                .put("meta", JsonObject.builder().put("z", true).putNull("a").build())
                .put("tags", JsonArray.of(JsonString.of("b"), JsonString.of("a")))
                .build();

        assertThat(CanonicalJson.write(value))
                .isEqualTo("{\"amount\":5E+3,\"meta\":{\"a\":null,\"z\":true},\"tags\":[\"b\",\"a\"],\"to\":\"ACME\"}");
    }

    @Test
    void memberOrderDoesNotChangeTheOutput() {
        JsonObject ab = JsonObject.builder().put("a", 1).put("b", 2).build();
        JsonObject ba = JsonObject.builder().put("b", 2).put("a", 1).build();

        assertThat(CanonicalJson.write(ab)).isEqualTo(CanonicalJson.write(ba));
    }

    @Test
    void sortsByUtf16CodeUnits() {
        JsonObject value = JsonObject.builder()
                .put("b", 1)
                .put("B", 1)
                .put("é", 1)
                .put("a", 1)
                .build();

        assertThat(CanonicalJson.write(value)).isEqualTo("{\"B\":1,\"a\":1,\"b\":1,\"é\":1}");
    }

    @ParameterizedTest
    @CsvSource({
        "1000, 1E+3",
        "1000.00, 1E+3",
        "0.000, 0",
        "-0, 0",
        "1000.10, 1000.1",
        "0.5, 0.5",
        "-12.340, -12.34",
        "123456789012345678901234567890.123, 123456789012345678901234567890.123",
        "0.0000001, 1E-7"
    })
    void writesEqualNumbersIdenticallyAndExactly(String input, String canonical) {
        assertThat(CanonicalJson.write(JsonNumber.of(new BigDecimal(input)))).isEqualTo(canonical);
    }

    @Test
    void escapesOnlyWhatJsonRequires() {
        String raw = "q\"b\\s/\b\f\n\r\t\u0001\u001f é 😀";

        assertThat(CanonicalJson.write(JsonString.of(raw)))
                .isEqualTo("\"q\\\"b\\\\s/\\b\\f\\n\\r\\t\\u0001\\u001f é 😀\"");
    }

    @Test
    void escapesLoneSurrogatesSoDifferentStringsNeverCollide() {
        String loneHigh = "a\ud83d";
        String loneLow = "a\ude00";

        assertThat(CanonicalJson.write(JsonString.of(loneHigh))).isEqualTo("\"a\\ud83d\"");
        assertThat(CanonicalJson.write(JsonString.of(loneLow))).isEqualTo("\"a\\ude00\"");
        assertThat(utf8(JsonString.of(loneHigh))).isNotEqualTo(utf8(JsonString.of("a?")));
    }

    @Test
    void hashesTheUtf8OfTheCanonicalForm() {
        // Reference values from `printf '{}' | sha256sum` and `printf '{"a":1}' | sha256sum`.
        assertThat(sha256Hex(JsonObject.empty()))
                .isEqualTo("44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a");
        assertThat(sha256Hex(JsonObject.builder().put("a", 1).build()))
                .isEqualTo("015abd7f5cc57a2dd94b7590f04ad8084273905ee33ec5cebeae62276a97f862");
    }

    private static byte[] utf8(JsonValue value) {
        return CanonicalJson.write(value).getBytes(StandardCharsets.UTF_8);
    }

    private static String sha256Hex(JsonValue value) {
        return HexFormat.of().formatHex(CanonicalJson.sha256(utf8(value)));
    }
}
