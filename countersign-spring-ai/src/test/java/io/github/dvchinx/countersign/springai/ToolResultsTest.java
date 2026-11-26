package io.github.dvchinx.countersign.springai;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dvchinx.countersign.core.guard.ResultRedaction;
import io.github.dvchinx.countersign.core.guard.ToolCallGuard;
import io.github.dvchinx.countersign.core.guard.Verdict;
import io.github.dvchinx.countersign.core.model.Decision;
import io.github.dvchinx.countersign.core.model.JsonObject;
import io.github.dvchinx.countersign.core.model.Redaction;
import io.github.dvchinx.countersign.core.model.ToolInvocation;
import io.github.dvchinx.countersign.core.policy.Policy;
import io.github.dvchinx.countersign.core.policy.PolicyEngine;
import io.github.dvchinx.countersign.core.receipt.ArgumentsHmac;
import io.github.dvchinx.countersign.core.receipt.Ed25519ReceiptSigner;
import io.github.dvchinx.countersign.core.receipt.ReceiptChain;
import io.github.dvchinx.countersign.core.redaction.FieldRedactor;
import io.github.dvchinx.countersign.core.redaction.PatternRedactor;
import io.github.dvchinx.countersign.core.redaction.RedactionRule;
import io.github.dvchinx.countersign.core.spi.Redactor;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;

class ToolResultsTest {

    /** A real redaction, as the guard hands it to the adapter. */
    private static ResultRedaction redaction(Redactor... redactors) {
        ToolCallGuard guard = ToolCallGuard.builder(
                        new PolicyEngine(
                                List.of(Policy.builder("all")
                                        .tools("*")
                                        .decision(Decision.allow())
                                        .build()),
                                Decision.deny("no")),
                        ReceiptChain.start(
                                "s",
                                new Ed25519ReceiptSigner(BankPolicies.KEYS.getPrivate()),
                                new ArgumentsHmac(new byte[32]),
                                Clock.systemUTC()),
                        receipt -> {})
                .redaction(RedactionRule.builder("r")
                        .tools("*")
                        .target(Redaction.Target.RESULT)
                        .redactors(redactors)
                        .build())
                .build();
        return ((Verdict.Proceed) guard.check(ToolInvocation.of("crm_find", JsonObject.empty()))).resultRedaction();
    }

    @Test
    void redactsAJsonResultAsATreeKeepingOrderAndNumbers() {
        String result = "{\"name\":\"Ana\",\"email\":\"ana@example.com\",\"balance\":5000,\"rate\":1.50,"
                + "\"card\":4111111111111111,\"tags\":[\"vip\"]}";

        String redacted = ToolResults.redact(
                result, redaction(FieldRedactor.of(List.of("email")), PatternRedactor.of("\\b\\d{16}\\b", "[CARD]")));

        assertThat(redacted)
                .isEqualTo("{\"name\":\"Ana\",\"email\":\"[REDACTED]\",\"balance\":5000,\"rate\":1.50,"
                        + "\"card\":\"[CARD]\",\"tags\":[\"vip\"]}");
    }

    @Test
    void redactsAToolThatReturnsAStringAsAJsonString() {
        // Spring AI writes a String return value as a JSON string.
        assertThat(ToolResults.redact(
                        "\"Card 4111111111111111 \\\"on file\\\"\"",
                        redaction(PatternRedactor.of("\\b\\d{16}\\b", "[CARD]"))))
                .isEqualTo("\"Card [CARD] \\\"on file\\\"\"");
    }

    @Test
    void redactsTextThatIsNotJsonAsOneString() {
        assertThat(ToolResults.redact(
                        "card: 4111111111111111, {not json", redaction(PatternRedactor.of("\\b\\d{16}\\b", "[CARD]"))))
                .isEqualTo("card: [CARD], {not json");
    }

    @Test
    void neverPassesOnADuplicateKeyTheRedactorsDidNotSee() {
        // A lenient parser keeps only the last "note"; the first one must not reach the model through the raw text.
        String result = "{\"note\":\"card 4111111111111111\",\"note\":\"ok\"}";

        assertThat(ToolResults.redact(result, redaction(PatternRedactor.of("\\b\\d{16}\\b", "[CARD]"))))
                .isEqualTo("{\"note\":\"ok\"}");
    }
}
