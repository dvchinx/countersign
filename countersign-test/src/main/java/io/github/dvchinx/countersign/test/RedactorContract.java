package io.github.dvchinx.countersign.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.github.dvchinx.countersign.core.model.JsonArray;
import io.github.dvchinx.countersign.core.model.JsonBoolean;
import io.github.dvchinx.countersign.core.model.JsonNull;
import io.github.dvchinx.countersign.core.model.JsonNumber;
import io.github.dvchinx.countersign.core.model.JsonObject;
import io.github.dvchinx.countersign.core.model.JsonString;
import io.github.dvchinx.countersign.core.model.JsonValue;
import io.github.dvchinx.countersign.core.receipt.CanonicalJson;
import io.github.dvchinx.countersign.core.spi.Redactor;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The tests every {@link Redactor} must pass. Passing them is the bar for merging a new redactor.
 *
 * <p>Extend this class in your redactor's test suite and implement {@link #redactor()}, {@link #sensitiveValue()} and
 * {@link #mustNotRemain()}. The contract checks that the data is really gone, wherever it sits in the tree, that the
 * redactor is deterministic (ADR 0015), and that it copes with every kind of JSON value a tool can return without
 * throwing, since a redactor that throws withholds the whole result.
 */
public abstract class RedactorContract {

    /** For subclasses. */
    protected RedactorContract() {}

    /**
     * Returns the redactor under test.
     *
     * @return the redactor; it may be the same instance for every test
     */
    protected abstract Redactor redactor();

    /**
     * Returns a value with the data this redactor removes, as a tool result would carry it.
     *
     * @return the value
     */
    protected abstract JsonValue sensitiveValue();

    /**
     * Returns the text that must not appear anywhere in the redacted value.
     *
     * @return the sensitive strings, for example a card number and an email
     */
    protected abstract List<String> mustNotRemain();

    @Test
    void removesTheSensitiveData() {
        String redacted = CanonicalJson.write(redactor().redact(sensitiveValue()));

        assertThat(redacted).doesNotContain(mustNotRemain());
    }

    @Test
    void removesTheSensitiveDataWhereverItSitsInTheTree() {
        JsonValue nested = JsonObject.builder()
                .put(
                        "data",
                        JsonArray.of(JsonObject.builder()
                                .put("item", sensitiveValue())
                                .build()))
                .build();

        String redacted = CanonicalJson.write(redactor().redact(nested));

        assertThat(redacted).doesNotContain(mustNotRemain());
    }

    @Test
    void isDeterministic() {
        assertThat(redactor().redact(sensitiveValue())).isEqualTo(redactor().redact(sensitiveValue()));
    }

    @Test
    void copesWithEveryKindOfJsonValue() {
        List<JsonValue> values = List.of(
                JsonNull.INSTANCE,
                JsonBoolean.of(true),
                JsonNumber.of(0),
                JsonNumber.of(new BigDecimal("-12345678901234567890.123")),
                JsonString.of(""),
                JsonString.of("plain text with \"quotes\", \\ and ñ 💸"),
                JsonObject.empty(),
                JsonArray.of(),
                JsonObject.builder()
                        .putNull("nothing")
                        .put("list", JsonArray.of(JsonNull.INSTANCE, JsonArray.of()))
                        .build());

        for (JsonValue value : values) {
            assertThatCode(() -> assertThat(redactor().redact(value)).isNotNull())
                    .as("redacting %s", value)
                    .doesNotThrowAnyException();
        }
    }
}
