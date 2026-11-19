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
import org.junit.jupiter.api.Test;

/** How mandatory policies, such as {@code @RequiresApproval}, combine with ordered policies. */
class MandatoryPolicyTest {

    private static final Decision FAIL_CLOSED = Decision.deny("No policy allows this call");

    private static final Policy ANNOTATION = Policy.builder("requires-approval.payments.transfer")
            .tools("payments.transfer")
            .decision(Decision.requireApproval("Moves real money"))
            .build();

    private static ToolInvocation transfer(long amount) {
        return ToolInvocation.of(
                "payments.transfer", JsonObject.builder().put("amount", amount).build());
    }

    private static EvaluationResult evaluate(List<Policy> ordered, long amount) {
        return new PolicyEngine(ordered, List.of(ANNOTATION), FAIL_CLOSED).evaluate(transfer(amount));
    }

    @Test
    void requiresApprovalEvenWhenAConfiguredPolicyAllowsTheCall() {
        EvaluationResult result = evaluate(
                List.of(Policy.builder("payments-allowed")
                        .tools("payments.*")
                        .decision(Decision.allow())
                        .build()),
                50);

        assertThat(result).isEqualTo(EvaluationResult.matched(ANNOTATION.id(), ANNOTATION.decision()));
    }

    @Test
    void letsAConfiguredDenyWin() {
        EvaluationResult result = evaluate(
                List.of(Policy.builder("too-large")
                        .tools("payments.transfer")
                        .when(invocation -> ((JsonNumber) invocation.arguments().get("amount"))
                                        .value()
                                        .compareTo(BigDecimal.valueOf(10_000))
                                > 0)
                        .decision(Decision.deny("Above the daily limit"))
                        .build()),
                50_000);

        assertThat(result.policyId()).isEqualTo("too-large");
        assertThat(result.decision()).isEqualTo(Decision.deny("Above the daily limit"));
    }

    @Test
    void makesTheToolUsableWithApprovalUnderADefaultDeny() {
        EvaluationResult result = evaluate(List.of(), 50);

        assertThat(result.basis()).isEqualTo(Basis.POLICY_MATCHED);
        assertThat(result.decision()).isEqualTo(Decision.requireApproval("Moves real money"));
    }

    @Test
    void keepsTheConfiguredApprovalAndReasonOnATie() {
        EvaluationResult result = evaluate(
                List.of(Policy.builder("configured")
                        .tools("payments.transfer")
                        .decision(Decision.requireApproval("Needs a second pair of eyes"))
                        .build()),
                50);

        assertThat(result.policyId()).isEqualTo("configured");
    }

    @Test
    void doesNotAffectOtherTools() {
        EvaluationResult result = new PolicyEngine(List.of(), List.of(ANNOTATION), FAIL_CLOSED)
                .evaluate(ToolInvocation.of("payments.balance", JsonObject.empty()));

        assertThat(result.basis()).isEqualTo(Basis.DEFAULT_DECISION);
    }

    @Test
    void takesTheStrictestOfSeveralMandatoryPolicies() {
        Policy mandatoryDeny = Policy.builder("frozen")
                .tools("payments.*")
                .decision(Decision.deny("Payments are frozen"))
                .build();

        EvaluationResult result =
                new PolicyEngine(List.of(), List.of(ANNOTATION, mandatoryDeny), FAIL_CLOSED).evaluate(transfer(1));

        assertThat(result.policyId()).isEqualTo("frozen");
    }

    @Test
    void deniesWhenAMandatoryConditionThrows() {
        Policy broken = Policy.builder("broken")
                .tools("payments.transfer")
                .when(invocation -> {
                    throw new IllegalStateException("bug");
                })
                .decision(Decision.requireApproval("x"))
                .build();

        EvaluationResult result = new PolicyEngine(
                        List.of(Policy.builder("ok")
                                .tools("*")
                                .decision(Decision.allow())
                                .build()),
                        List.of(broken),
                        FAIL_CLOSED)
                .evaluate(transfer(1));

        assertThat(result.basis()).isEqualTo(Basis.EVALUATION_FAILED);
        assertThat(result.policyId()).isEqualTo("broken");
    }

    @Test
    void rejectsAMandatoryPolicyThatAllows() {
        Policy allow = Policy.builder("pointless")
                .tools("*")
                .decision(Decision.allow())
                .build();

        assertThatThrownBy(() -> new PolicyEngine(List.of(), List.of(allow), FAIL_CLOSED))
                .isInstanceOf(InvalidPolicyException.class)
                .hasMessageContaining("pointless");
    }

    @Test
    void rejectsIdsSharedBetweenOrderedAndMandatoryPolicies() {
        Policy same = Policy.builder(ANNOTATION.id())
                .tools("*")
                .decision(Decision.allow())
                .build();

        assertThatThrownBy(() -> new PolicyEngine(List.of(same), List.of(ANNOTATION), FAIL_CLOSED))
                .isInstanceOf(InvalidPolicyException.class);
    }
}
