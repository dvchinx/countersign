package io.github.dvchinx.countersign.springai.annotation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dvchinx.countersign.core.model.Decision;
import io.github.dvchinx.countersign.core.policy.InvalidPolicyException;
import io.github.dvchinx.countersign.core.policy.Policy;
import io.github.dvchinx.countersign.springai.annotation.misplaced.MisplacedAnnotation;
import io.github.dvchinx.countersign.springai.annotation.tools.AnnotatedTools;
import java.util.List;
import org.junit.jupiter.api.Test;

class RequiresApprovalPoliciesTest {

    private static final String TOOLS_PACKAGE = "io.github.dvchinx.countersign.springai.annotation.tools";

    private static void assertPoliciesForAnnotatedTools(List<Policy> policies) {
        assertThat(policies)
                .extracting(Policy::id)
                .containsExactlyInAnyOrder("requires-approval.payments.transfer", "requires-approval.closeAccount");
        Policy transfer = policies.stream()
                .filter(p -> p.id().equals("requires-approval.payments.transfer"))
                .findFirst()
                .orElseThrow();
        assertThat(transfer.decision()).isEqualTo(Decision.requireApproval("Moves real money"));
        assertThat(transfer.targets("payments.transfer")).isTrue();
        assertThat(transfer.targets("payments.balance")).isFalse();
    }

    @Test
    void scansPackagesForAnnotatedToolsWithoutNeedingBeans() {
        assertPoliciesForAnnotatedTools(
                RequiresApprovalPolicies.scan(List.of(TOOLS_PACKAGE), getClass().getClassLoader()));
    }

    @Test
    void readsAnnotatedToolsFromClasses() {
        assertPoliciesForAnnotatedTools(RequiresApprovalPolicies.from(AnnotatedTools.class));
    }

    @Test
    void namesTheToolInTheDefaultReason() {
        Policy close = RequiresApprovalPolicies.from(AnnotatedTools.class).stream()
                .filter(p -> p.id().equals("requires-approval.closeAccount"))
                .findFirst()
                .orElseThrow();

        assertThat(close.decision()).isEqualTo(Decision.requireApproval("Tool 'closeAccount' requires human approval"));
    }

    @Test
    void refusesAnAnnotationThatWouldHaveNoEffect() {
        assertThatThrownBy(() -> RequiresApprovalPolicies.from(MisplacedAnnotation.class))
                .isInstanceOf(InvalidPolicyException.class)
                .hasMessageContaining("MisplacedAnnotation.transfer()")
                .hasMessageContaining("not a @Tool");
        assertThatThrownBy(() -> RequiresApprovalPolicies.scan(
                        List.of("io.github.dvchinx.countersign.springai.annotation.misplaced"),
                        getClass().getClassLoader()))
                .isInstanceOf(InvalidPolicyException.class);
    }

    @Test
    void findsNothingWhereThereIsNothing() {
        assertThat(RequiresApprovalPolicies.scan(
                        List.of("io.github.dvchinx.countersign.nothing.here"),
                        getClass().getClassLoader()))
                .isEmpty();
        assertThat(RequiresApprovalPolicies.from(String.class)).isEmpty();
    }
}
