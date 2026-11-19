package io.github.dvchinx.countersign.core.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;

class JsonValueTest {

    @Test
    void numbersWithTheSameValueAreEqualWhateverTheirScale() {
        JsonNumber plain = JsonNumber.of(new BigDecimal("1000"));
        JsonNumber withDecimals = JsonNumber.of(new BigDecimal("1000.00"));

        assertThat(plain).isEqualTo(withDecimals).hasSameHashCodeAs(withDecimals);
        assertThat(JsonNumber.of(new BigDecimal("0.0")))
                .isEqualTo(JsonNumber.of(0))
                .hasSameHashCodeAs(JsonNumber.of(0));
        assertThat(JsonNumber.of(1000)).isNotEqualTo(JsonNumber.of(1001));
    }

    @Test
    void keepsTheExactDecimalValueOfADouble() {
        assertThat(JsonNumber.of(1000.1).value()).isEqualByComparingTo("1000.1");
    }

    @Test
    void rejectsNumbersJsonCannotRepresent() {
        assertThatThrownBy(() -> JsonNumber.of(Double.NaN)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> JsonNumber.of(Double.POSITIVE_INFINITY)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void objectIsACopyThatCallersCannotChange() {
        Map<String, JsonValue> source = new HashMap<>();
        source.put("amount", JsonNumber.of(5));
        JsonObject object = new JsonObject(source);

        source.put("amount", JsonNumber.of(5_000_000));

        assertThat(object.get("amount")).isEqualTo(JsonNumber.of(5));
        assertThatThrownBy(() -> object.members().put("to", JsonString.of("x")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void objectKeepsMemberOrderButEqualityIgnoresIt() {
        JsonObject ab = JsonObject.builder().put("a", 1).put("b", 2).build();
        JsonObject ba = JsonObject.builder().put("b", 2).put("a", 1).build();

        assertThat(ab.members().keySet()).containsExactly("a", "b");
        assertThat(ab).isEqualTo(ba);
    }

    @Test
    void rejectsJavaNullInsteadOfJsonNull() {
        Map<String, JsonValue> withNull = new HashMap<>();
        withNull.put("to", null);

        assertThatThrownBy(() -> new JsonObject(withNull)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> JsonString.of(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void convertsToPlainJavaForExpressionLanguages() {
        JsonObject args = JsonObject.builder()
                .put("amount", new BigDecimal("5000.50"))
                .put("to", "ACME")
                .put("urgent", true)
                .putNull("memo")
                .put("tags", JsonArray.of(JsonString.of("rent"), JsonNull.INSTANCE))
                .put("meta", JsonObject.builder().put("source", "chat").build())
                .build();

        Map<String, Object> java = args.toJava();

        assertThat(java)
                .containsEntry("amount", new BigDecimal("5000.50"))
                .containsEntry("to", "ACME")
                .containsEntry("urgent", true)
                .containsEntry("memo", null)
                .containsEntry("meta", Map.of("source", "chat"));
        assertThat(java.get("tags"))
                .asInstanceOf(InstanceOfAssertFactories.LIST)
                .containsExactly("rent", null);
        assertThatThrownBy(() -> java.put("amount", 1)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void neverPrintsArgumentValues() {
        JsonObject args = JsonObject.builder()
                .put("iban", "DE89370400440532013000")
                .put("amount", 987654)
                .build();
        ToolInvocation invocation = ToolInvocation.of("payments.transfer", args);

        assertThat(invocation.toString()).contains("iban", "amount").doesNotContain("DE89370400440532013000", "987654");
        assertThat(JsonArray.of(JsonString.of("secret")).toString()).doesNotContain("secret");
    }
}
