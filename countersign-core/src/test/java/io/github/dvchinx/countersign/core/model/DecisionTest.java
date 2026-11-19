package io.github.dvchinx.countersign.core.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class DecisionTest {

    @Test
    void reportsItsType() {
        assertThat(Decision.allow().type()).isEqualTo(Decision.Type.ALLOW);
        assertThat(Decision.deny("no").type()).isEqualTo(Decision.Type.DENY);
        assertThat(Decision.requireApproval("moves money").type()).isEqualTo(Decision.Type.REQUIRE_APPROVAL);
    }

    @Test
    void refusalsAndApprovalsMustExplainThemselves() {
        assertThatThrownBy(() -> Decision.deny(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Decision.requireApproval("")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void failedEvaluationAlwaysDenies() {
        EvaluationResult failed = EvaluationResult.failed("broken-policy");

        assertThat(failed.decision()).isInstanceOf(Decision.Deny.class);
        assertThat(failed.basis()).isEqualTo(EvaluationResult.Basis.EVALUATION_FAILED);
        assertThatThrownBy(() -> new EvaluationResult(
                        Decision.allow(), EvaluationResult.Basis.EVALUATION_FAILED, "broken-policy"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void onlyTheDefaultDecisionHasNoPolicyId() {
        assertThatThrownBy(() -> new EvaluationResult(Decision.allow(), EvaluationResult.Basis.POLICY_MATCHED, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                        new EvaluationResult(Decision.allow(), EvaluationResult.Basis.DEFAULT_DECISION, "some-policy"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invalidArgumentsAlwaysDenyWithoutAPolicy() {
        EvaluationResult invalid = EvaluationResult.invalidArguments();

        assertThat(invalid.decision()).isInstanceOf(Decision.Deny.class);
        assertThat(invalid.policyId()).isNull();
        assertThatThrownBy(() -> new EvaluationResult(Decision.allow(), EvaluationResult.Basis.INVALID_ARGUMENTS, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EvaluationResult(
                        Decision.deny("x"), EvaluationResult.Basis.INVALID_ARGUMENTS, "some-policy"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void approvalAnswersNameWhoGaveThem() {
        assertThat(ApprovalDecision.approved("bob").approver()).isEqualTo("bob");
        assertThatThrownBy(() -> ApprovalDecision.approved(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ApprovalDecision.rejected("", "no")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invocationNeedsAToolName() {
        assertThatThrownBy(() -> ToolInvocation.of(" ", JsonObject.empty()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
