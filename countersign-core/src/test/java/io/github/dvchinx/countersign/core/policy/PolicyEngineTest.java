package io.github.dvchinx.countersign.core.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dvchinx.countersign.core.model.Decision;
import io.github.dvchinx.countersign.core.model.EvaluationResult;
import io.github.dvchinx.countersign.core.model.EvaluationResult.Basis;
import io.github.dvchinx.countersign.core.model.JsonNumber;
import io.github.dvchinx.countersign.core.model.JsonObject;
import io.github.dvchinx.countersign.core.model.ToolInvocation;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PolicyEngineTest {

    private static final Decision FAIL_CLOSED = Decision.deny("No policy allows this call");

    private static final Condition AMOUNT_ABOVE_1000 = invocation -> {
        JsonNumber amount = (JsonNumber) invocation.arguments().get("amount");
        return amount != null && amount.value().compareTo(BigDecimal.valueOf(1000)) > 0;
    };

    private static ToolInvocation transfer(long amount) {
        return ToolInvocation.of(
                "payments.transfer", JsonObject.builder().put("amount", amount).build());
    }

    @Test
    void deniesByDefaultWhenThereAreNoPolicies() {
        EvaluationResult result = new PolicyEngine(List.of(), FAIL_CLOSED).evaluate(transfer(10));

        assertThat(result).isEqualTo(EvaluationResult.defaultDecision(FAIL_CLOSED));
        assertThat(result.policyId()).isNull();
    }

    @Test
    void requiresApprovalForTransferAboveLimit() {
        PolicyEngine engine = new PolicyEngine(
                List.of(
                        Policy.builder("transfers-need-approval")
                                .tools("payments.transfer")
                                .when(AMOUNT_ABOVE_1000)
                                .decision(Decision.requireApproval("Moves real money"))
                                .build(),
                        Policy.builder("payments-allowed")
                                .tools("payments.*")
                                .decision(Decision.allow())
                                .build()),
                FAIL_CLOSED);

        assertThat(engine.evaluate(transfer(5000)))
                .isEqualTo(EvaluationResult.matched(
                        "transfers-need-approval", Decision.requireApproval("Moves real money")));
        assertThat(engine.evaluate(transfer(500)))
                .isEqualTo(EvaluationResult.matched("payments-allowed", Decision.allow()));
    }

    @Test
    void firstMatchingPolicyWinsAndLaterOnesAreNotEvaluated() {
        AtomicInteger laterEvaluations = new AtomicInteger();
        PolicyEngine engine = new PolicyEngine(
                List.of(
                        Policy.builder("first")
                                .tools("*")
                                .decision(Decision.deny("first"))
                                .build(),
                        Policy.builder("second")
                                .tools("*")
                                .when(invocation -> laterEvaluations.incrementAndGet() > 0)
                                .decision(Decision.allow())
                                .build()),
                FAIL_CLOSED);

        assertThat(engine.evaluate(transfer(1)).policyId()).isEqualTo("first");
        assertThat(laterEvaluations).hasValue(0);
    }

    @Test
    void lowerPriorityRunsFirstAndTiesKeepTheGivenOrder() {
        Policy allowAll = Policy.builder("allow-all")
                .tools("*")
                .decision(Decision.allow())
                .priority(100)
                .build();
        Policy tieA = Policy.builder("tie-a")
                .tools("*")
                .decision(Decision.deny("a"))
                .priority(10)
                .build();
        Policy tieB = Policy.builder("tie-b")
                .tools("*")
                .decision(Decision.deny("b"))
                .priority(10)
                .build();
        Policy urgent = Policy.builder("urgent")
                .tools("*")
                .decision(Decision.deny("u"))
                .priority(-5)
                .build();

        PolicyEngine engine = new PolicyEngine(List.of(allowAll, tieA, tieB, urgent), FAIL_CLOSED);

        assertThat(engine.policies()).extracting(Policy::id).containsExactly("urgent", "tie-a", "tie-b", "allow-all");
        assertThat(engine.evaluate(transfer(1)).policyId()).isEqualTo("urgent");
    }

    @Test
    void fallsBackToTheDefaultWhenNoPolicyTargetsTheTool() {
        PolicyEngine engine = new PolicyEngine(
                List.of(Policy.builder("db-reads")
                        .tools("db.read*")
                        .decision(Decision.allow())
                        .build()),
                FAIL_CLOSED);

        assertThat(engine.evaluate(transfer(1)).basis()).isEqualTo(Basis.DEFAULT_DECISION);
        assertThat(engine.evaluate(transfer(1)).decision()).isEqualTo(FAIL_CLOSED);
    }

    @Test
    void fallsBackToTheDefaultWhenTheConditionDoesNotHold() {
        PolicyEngine engine = new PolicyEngine(
                List.of(Policy.builder("big-transfers")
                        .tools("payments.transfer")
                        .when(AMOUNT_ABOVE_1000)
                        .decision(Decision.allow())
                        .build()),
                FAIL_CLOSED);

        assertThat(engine.evaluate(transfer(10)).basis()).isEqualTo(Basis.DEFAULT_DECISION);
    }

    @Test
    void deniesWhenAConditionThrowsEvenIfALaterPolicyWouldAllow() {
        PolicyEngine engine = new PolicyEngine(
                List.of(
                        Policy.builder("broken")
                                .tools("payments.*")
                                .when(invocation -> {
                                    throw new IllegalStateException("boom");
                                })
                                .decision(Decision.deny("never reached"))
                                .build(),
                        Policy.builder("allow-everything")
                                .tools("*")
                                .decision(Decision.allow())
                                .build()),
                Decision.allow());

        EvaluationResult result = engine.evaluate(transfer(1));

        assertThat(result.basis()).isEqualTo(Basis.EVALUATION_FAILED);
        assertThat(result.policyId()).isEqualTo("broken");
        assertThat(result.decision()).isInstanceOf(Decision.Deny.class);
    }

    @Test
    void doesNotEvaluateConditionsOfPoliciesForOtherTools() {
        PolicyEngine engine = new PolicyEngine(
                List.of(Policy.builder("db-only")
                        .tools("db.*")
                        .when(invocation -> {
                            throw new IllegalStateException("must not run for payments");
                        })
                        .decision(Decision.deny("db"))
                        .build()),
                FAIL_CLOSED);

        assertThat(engine.evaluate(transfer(1)).basis()).isEqualTo(Basis.DEFAULT_DECISION);
    }

    @Test
    void sameInvocationAndPoliciesGiveTheSameDecision() {
        PolicyEngine engine = new PolicyEngine(
                List.of(Policy.builder("approval")
                        .tools("payments.transfer")
                        .when(AMOUNT_ABOVE_1000)
                        .decision(Decision.requireApproval("Moves real money"))
                        .build()),
                FAIL_CLOSED);
        ToolInvocation invocation = transfer(5000);

        assertThat(engine.evaluate(invocation)).isEqualTo(engine.evaluate(invocation));
    }

    @Test
    void rejectsDuplicatePolicyIds() {
        Policy one =
                Policy.builder("same").tools("*").decision(Decision.allow()).build();
        Policy two = Policy.builder("same")
                .tools("db.*")
                .decision(Decision.deny("x"))
                .build();

        assertThatThrownBy(() -> new PolicyEngine(List.of(one, two), FAIL_CLOSED))
                .isInstanceOf(InvalidPolicyException.class)
                .hasMessageContaining("same");
    }
}
