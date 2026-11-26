package io.github.dvchinx.countersign.test;

import io.github.dvchinx.countersign.core.model.JsonObject;
import io.github.dvchinx.countersign.core.model.JsonValue;
import io.github.dvchinx.countersign.core.redaction.FieldRedactor;
import io.github.dvchinx.countersign.core.redaction.PatternRedactor;
import io.github.dvchinx.countersign.core.spi.Redactor;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Nested;

/** The built-in redactors pass the contract every redactor must pass. */
class BuiltInRedactorContractTest {

    @Nested
    class Pattern extends RedactorContract {

        private final Redactor redactor = PatternRedactor.of("\\b\\d(?:[ -]?\\d){12,18}\\b", "[CARD]");

        @Override
        protected Redactor redactor() {
            return redactor;
        }

        @Override
        protected JsonValue sensitiveValue() {
            return JsonObject.builder()
                    .put("card", "4111 1111 1111 1111")
                    .put("cardNumber", new BigDecimal("5500000000000004"))
                    .put("note", "paid with 378282246310005 yesterday")
                    .build();
        }

        @Override
        protected List<String> mustNotRemain() {
            return List.of("4111", "5500000000000004", "378282246310005");
        }
    }

    @Nested
    class Fields extends RedactorContract {

        private final Redactor redactor = FieldRedactor.of(List.of("email", "phone"));

        @Override
        protected Redactor redactor() {
            return redactor;
        }

        @Override
        protected JsonValue sensitiveValue() {
            return JsonObject.builder()
                    .put("Email", "ana@example.com")
                    .put(
                            "phone",
                            JsonObject.builder()
                                    .put("mobile", "+57 300 000 0000")
                                    .build())
                    .build();
        }

        @Override
        protected List<String> mustNotRemain() {
            return List.of("ana@example.com", "+57 300 000 0000");
        }
    }
}
