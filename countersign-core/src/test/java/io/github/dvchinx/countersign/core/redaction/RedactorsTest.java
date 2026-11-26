package io.github.dvchinx.countersign.core.redaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dvchinx.countersign.core.model.JsonArray;
import io.github.dvchinx.countersign.core.model.JsonBoolean;
import io.github.dvchinx.countersign.core.model.JsonNull;
import io.github.dvchinx.countersign.core.model.JsonNumber;
import io.github.dvchinx.countersign.core.model.JsonObject;
import io.github.dvchinx.countersign.core.model.JsonString;
import io.github.dvchinx.countersign.core.model.JsonValue;
import io.github.dvchinx.countersign.core.policy.InvalidPolicyException;
import io.github.dvchinx.countersign.core.spi.Redactor;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/** The built-in redactors and {@link Redactor#strings}. */
class RedactorsTest {

    private static final String CARD = "\\b\\d(?:[ -]?\\d){12,18}\\b";

    private static final JsonObject CUSTOMER = JsonObject.builder()
            .put("name", "Ana Pérez")
            .put("Email", "ana@example.com")
            .put("card", "4111 1111 1111 1111")
            .put("cardAsNumber", new BigDecimal("4111111111111111"))
            .put("balance", 1250)
            .put("vip", true)
            .putNull("notes")
            .put(
                    "contacts",
                    JsonArray.of(JsonObject.builder()
                            .put("phone", "+57 300 000 0000")
                            .put("note", "card 4111111111111111 on file")
                            .build()))
            .build();

    @Test
    void patternRedactorReplacesMatchesInStringsAndKeepsTheShape() {
        JsonObject redacted = (JsonObject) PatternRedactor.of(CARD, "[CARD]").redact(CUSTOMER);

        assertThat(redacted.members().keySet())
                .containsExactlyElementsOf(CUSTOMER.members().keySet());
        assertThat(redacted.get("card")).isEqualTo(JsonString.of("[CARD]"));
        assertThat(redacted.get("name")).isEqualTo(JsonString.of("Ana Pérez"));
        JsonObject contact = (JsonObject) ((JsonArray) redacted.get("contacts")).get(0);
        assertThat(contact.get("note")).isEqualTo(JsonString.of("card [CARD] on file"));
    }

    @Test
    void patternRedactorReplacesANumberWhoseDigitsMatch() {
        JsonObject redacted = (JsonObject) PatternRedactor.of(CARD, "[CARD]").redact(CUSTOMER);

        assertThat(redacted.get("cardAsNumber")).isEqualTo(JsonString.of("[CARD]"));
        assertThat(redacted.get("balance")).isEqualTo(JsonNumber.of(1250));
        assertThat(redacted.get("vip")).isEqualTo(JsonBoolean.of(true));
        assertThat(redacted.get("notes")).isEqualTo(JsonNull.INSTANCE);
    }

    @Test
    void patternRedactorReadsLargeNumbersAsPlainDigits() {
        // 4.111111111111111E+15 would not match a digits-only pattern.
        JsonValue number = JsonNumber.of(new BigDecimal("4.111111111111111E+15"));

        assertThat(PatternRedactor.of(CARD, "[CARD]").redact(number)).isEqualTo(JsonString.of("[CARD]"));
    }

    @Test
    void patternRedactorTreatsTheReplacementAsLiteralText() {
        assertThat(PatternRedactor.of("\\d+", "[US$ \\1]").redact(JsonString.of("paid 300")))
                .isEqualTo(JsonString.of("paid [US$ \\1]"));
    }

    @Test
    void patternRedactorUsesADefaultReplacement() {
        assertThat(PatternRedactor.of("secret").redact(JsonString.of("a secret")))
                .isEqualTo(JsonString.of("a [REDACTED]"));
    }

    @Test
    void patternRedactorRejectsPatternsThatCannotWork() {
        assertThatThrownBy(() -> PatternRedactor.of("  ")).isInstanceOf(InvalidPolicyException.class);
        assertThatThrownBy(() -> PatternRedactor.of("(unclosed"))
                .isInstanceOf(InvalidPolicyException.class)
                .hasMessageContaining("not a valid regular expression");
        assertThatThrownBy(() -> PatternRedactor.of("\\d*"))
                .isInstanceOf(InvalidPolicyException.class)
                .hasMessageContaining("matches the empty string");
    }

    @Test
    void fieldRedactorReplacesTheWholeValueUnderAKeyAtAnyDepthIgnoringCase() {
        JsonObject redacted = (JsonObject) FieldRedactor.of(List.of("email", "PHONE", "contacts_none"), "[CONTACT]")
                .redact(CUSTOMER);

        assertThat(redacted.get("Email")).isEqualTo(JsonString.of("[CONTACT]"));
        JsonObject contact = (JsonObject) ((JsonArray) redacted.get("contacts")).get(0);
        assertThat(contact.get("phone")).isEqualTo(JsonString.of("[CONTACT]"));
        assertThat(contact.get("note")).isEqualTo(JsonString.of("card 4111111111111111 on file"));
        assertThat(redacted.get("card")).isEqualTo(JsonString.of("4111 1111 1111 1111"));
    }

    @Test
    void fieldRedactorReplacesObjectsAndArraysWhole() {
        JsonObject redacted = (JsonObject) FieldRedactor.of(List.of("contacts")).redact(CUSTOMER);

        assertThat(redacted.get("contacts")).isEqualTo(JsonString.of("[REDACTED]"));
    }

    @Test
    void fieldRedactorLeavesPlainTextAlone() {
        JsonValue text = JsonString.of("email: ana@example.com");

        assertThat(FieldRedactor.of(List.of("email")).redact(text)).isEqualTo(text);
    }

    @Test
    void fieldRedactorNeedsFieldNames() {
        assertThatThrownBy(() -> FieldRedactor.of(List.of())).isInstanceOf(InvalidPolicyException.class);
        assertThatThrownBy(() -> FieldRedactor.of(List.of("email", " "))).isInstanceOf(InvalidPolicyException.class);
    }

    @Test
    void stringsAppliesAFunctionToEveryStringValueButNotToKeys() {
        Redactor upper = Redactor.strings(text -> text.toUpperCase(Locale.ROOT));

        JsonObject redacted = (JsonObject) upper.redact(CUSTOMER);

        assertThat(redacted.members().keySet())
                .containsExactlyElementsOf(CUSTOMER.members().keySet());
        assertThat(redacted.get("name")).isEqualTo(JsonString.of("ANA PÉREZ"));
        assertThat(redacted.get("balance")).isEqualTo(JsonNumber.of(1250));
    }

    @Test
    void redactorsAreDeterministic() {
        List<Redactor> redactors = List.of(
                PatternRedactor.of(CARD, "[CARD]"),
                FieldRedactor.of(List.of("email")),
                Redactor.strings(text -> text.replace('a', '*')));

        for (Redactor redactor : redactors) {
            assertThat(redactor.redact(CUSTOMER)).isEqualTo(redactor.redact(CUSTOMER));
        }
    }
}
