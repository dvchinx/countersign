package io.github.dvchinx.countersign.springai;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dvchinx.countersign.core.model.JsonArray;
import io.github.dvchinx.countersign.core.model.JsonNull;
import io.github.dvchinx.countersign.core.model.JsonNumber;
import io.github.dvchinx.countersign.core.model.JsonObject;
import io.github.dvchinx.countersign.core.model.JsonString;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ToolInputParserTest {

    @Test
    void parsesAllJsonTypesKeepingExactNumbers() {
        JsonObject parsed = ToolInputParser.parse(
                "{\"amount\": 1000.10, \"count\": 12345678901234567890, \"to\": \"ACME\", \"urgent\": true,"
                        + " \"memo\": null, \"tags\": [\"rent\", 1], \"meta\": {\"source\": \"chat\"}}");

        assertThat(parsed).isNotNull();
        assertThat(parsed.get("amount")).isEqualTo(JsonNumber.of(new BigDecimal("1000.1")));
        assertThat(((JsonNumber) parsed.get("amount")).value()).isEqualByComparingTo("1000.10");
        assertThat(parsed.get("count")).isEqualTo(JsonNumber.of(new BigDecimal("12345678901234567890")));
        assertThat(parsed.get("to")).isEqualTo(JsonString.of("ACME"));
        assertThat(parsed.get("memo")).isEqualTo(JsonNull.INSTANCE);
        assertThat(parsed.get("tags")).isEqualTo(JsonArray.of(JsonString.of("rent"), JsonNumber.of(1)));
        assertThat(parsed.get("meta"))
                .isEqualTo(JsonObject.builder().put("source", "chat").build());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  "})
    void treatsBlankInputAsNoArguments(String input) {
        assertThat(ToolInputParser.parse(input)).isEqualTo(JsonObject.empty());
        assertThat(ToolInputParser.parse(null)).isEqualTo(JsonObject.empty());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"amount\": 1, \"amount\": 5000}",
                "{\"amount\": 1} {\"amount\": 5000}",
                "{\"amount\": 1",
                "[1, 2]",
                "\"text\"",
                "42",
                "null",
                "{amount: 1}",
                "{'amount': 1}"
            })
    void rejectsInputThatIsNotExactlyOneWellFormedObject(String input) {
        assertThat(ToolInputParser.parse(input)).isNull();
    }
}
