package io.github.dvchinx.countersign.core.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dvchinx.countersign.core.model.Decision;
import java.util.List;
import org.junit.jupiter.api.Test;

class PolicyTest {

    @Test
    void targetsAToolWhenAnyPatternMatches() {
        Policy policy = Policy.builder("reads")
                .tools("db.select", "files.read*")
                .decision(Decision.allow())
                .build();

        assertThat(policy.targets("files.readAll")).isTrue();
        assertThat(policy.targets("db.select")).isTrue();
        assertThat(policy.targets("db.delete")).isFalse();
    }

    @Test
    void rejectsPolicyWithoutTools() {
        assertThatThrownBy(() ->
                        Policy.builder("nothing").decision(Decision.allow()).build())
                .isInstanceOf(InvalidPolicyException.class)
                .hasMessageContaining("nothing");
    }

    @Test
    void rejectsPolicyWithoutDecision() {
        assertThatThrownBy(() -> Policy.builder("undecided").tools("*").build())
                .isInstanceOf(InvalidPolicyException.class)
                .hasMessageContaining("undecided");
    }

    @Test
    void rejectsIdsThatWouldBreakReceiptsOrMetricTags() {
        assertThatThrownBy(() -> new Policy("", List.of(ToolPattern.of("*")), Condition.always(), Decision.allow(), 0))
                .isInstanceOf(InvalidPolicyException.class);
        assertThatThrownBy(() -> Policy.builder("has space")
                        .tools("*")
                        .decision(Decision.allow())
                        .build())
                .isInstanceOf(InvalidPolicyException.class);
    }

    @Test
    void defaultsToAlwaysMatchingAndDefaultPriority() {
        Policy policy =
                Policy.builder("simple").tools("*").decision(Decision.allow()).build();

        assertThat(policy.priority()).isEqualTo(Policy.DEFAULT_PRIORITY);
        assertThat(policy.condition()
                        .matches(io.github.dvchinx.countersign.core.model.ToolInvocation.of(
                                "any", io.github.dvchinx.countersign.core.model.JsonObject.empty())))
                .isTrue();
    }
}
