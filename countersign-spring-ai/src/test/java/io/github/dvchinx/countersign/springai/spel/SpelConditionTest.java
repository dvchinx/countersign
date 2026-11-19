package io.github.dvchinx.countersign.springai.spel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dvchinx.countersign.core.model.Decision;
import io.github.dvchinx.countersign.core.model.EvaluationResult;
import io.github.dvchinx.countersign.core.model.JsonObject;
import io.github.dvchinx.countersign.core.model.ToolInvocation;
import io.github.dvchinx.countersign.core.policy.InvalidPolicyException;
import io.github.dvchinx.countersign.core.policy.Policy;
import io.github.dvchinx.countersign.core.policy.PolicyEngine;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.expression.spel.SpelEvaluationException;

class SpelConditionTest {

    private static ToolInvocation transfer(String amount) {
        return ToolInvocation.of(
                "payments.transfer",
                JsonObject.builder()
                        .put("amount", new BigDecimal(amount))
                        .put("to", "ACME")
                        .build());
    }

    private static ToolInvocation query(String sql) {
        return ToolInvocation.of(
                "db.query", JsonObject.builder().put("query", sql).build());
    }

    @Test
    void comparesNumericArgumentsExactly() {
        SpelCondition overLimit = SpelCondition.parse("#args['amount'] > 1000");

        assertThat(overLimit.matches(transfer("1000.01"))).isTrue();
        assertThat(overLimit.matches(transfer("1000.00"))).isFalse();
        assertThat(SpelCondition.parse("#args['amount'] >= 1000.5").matches(transfer("1000.5")))
                .isTrue();
    }

    @Test
    void matchesTextWithRegularExpressions() {
        SpelCondition destructive =
                SpelCondition.parse("#args['query'] matches '(?i).*\\b(drop|truncate|delete)\\b.*'");

        assertThat(destructive.matches(query("DROP TABLE accounts"))).isTrue();
        assertThat(destructive.matches(query("select * from accounts"))).isFalse();
    }

    @Test
    void exposesToolPrincipalAndConversation() {
        ToolInvocation invocation =
                new ToolInvocation("inv-1", "payments.transfer", JsonObject.empty(), "alice", "conv-7", Instant.now());

        assertThat(SpelCondition.parse("#tool == 'payments.transfer' and #principal == 'alice'")
                        .matches(invocation))
                .isTrue();
        assertThat(SpelCondition.parse("#conversationId == 'conv-7'").matches(invocation))
                .isTrue();
        assertThat(SpelCondition.parse("#principal == null").matches(transfer("1")))
                .isTrue();
    }

    @Test
    void treatsAMissingArgumentAsNull() {
        assertThat(SpelCondition.parse("#args['missing'] == null").matches(transfer("1")))
                .isTrue();
        assertThat(SpelCondition.parse("#args['missing'] > 1000").matches(transfer("1")))
                .isFalse();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "T(java.lang.Runtime).getRuntime().exec('touch /tmp/pwned') != null",
                "new java.io.File('/etc/passwd').exists()",
                "@someBean != null",
                "#args['to'].toUpperCase() == 'ACME'",
                "#args['to'].getClass().getName() != null",
                "(#args['amount'] = 0) == 0",
                "(#tool = 'other') == 'other'"
            })
    void sandboxRejectsAnythingButReadingTheInvocation(String attack) {
        SpelCondition condition = SpelCondition.parse(attack);

        assertThatThrownBy(() -> condition.matches(transfer("5000"))).isInstanceOf(SpelEvaluationException.class);
    }

    @Test
    void rejectsNonBooleanResults() {
        assertThatThrownBy(() -> SpelCondition.parse("#args['amount']").matches(transfer("1")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("boolean");
        assertThatThrownBy(() -> SpelCondition.parse("#args['missing']").matches(transfer("1")))
                .isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "#args['amount'] >", "#args[["})
    void rejectsInvalidExpressionsAtParseTime(String expression) {
        assertThatThrownBy(() -> SpelCondition.parse(expression)).isInstanceOf(InvalidPolicyException.class);
    }

    @Test
    void deniesTransferWhenTheConditionIsAnAttack() {
        PolicyEngine engine = new PolicyEngine(
                List.of(
                        Policy.builder("sneaky")
                                .tools("payments.*")
                                .when(SpelCondition.parse("T(java.lang.System).exit(1) == null"))
                                .decision(Decision.allow())
                                .build(),
                        Policy.builder("allow-all")
                                .tools("*")
                                .decision(Decision.allow())
                                .build()),
                Decision.allow());

        EvaluationResult result = engine.evaluate(transfer("5000"));

        assertThat(result.basis()).isEqualTo(EvaluationResult.Basis.EVALUATION_FAILED);
        assertThat(result.decision()).isInstanceOf(Decision.Deny.class);
    }

    @Test
    void requiresApprovalForTransferAboveLimitConfiguredInSpel() {
        PolicyEngine engine = new PolicyEngine(
                List.of(Policy.builder("transfers-need-approval")
                        .tools("payments.transfer")
                        .when(SpelCondition.parse("#args['amount'] > 1000"))
                        .decision(Decision.requireApproval("Moves real money"))
                        .build()),
                Decision.deny("No policy allows this call"));

        assertThat(engine.evaluate(transfer("5000")).decision().type()).isEqualTo(Decision.Type.REQUIRE_APPROVAL);
        assertThat(engine.evaluate(transfer("50")).basis()).isEqualTo(EvaluationResult.Basis.DEFAULT_DECISION);
    }
}
