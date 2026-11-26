package io.github.dvchinx.countersign.core.redaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dvchinx.countersign.core.model.JsonObject;
import io.github.dvchinx.countersign.core.model.Redaction;
import io.github.dvchinx.countersign.core.model.ToolInvocation;
import io.github.dvchinx.countersign.core.policy.InvalidPolicyException;
import io.github.dvchinx.countersign.core.spi.Redactor;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RedactionRuleTest {

    private static final Redactor NOTHING = value -> value;

    private static RedactionRule.Builder rule(String id) {
        return RedactionRule.builder(id)
                .tools("crm_*")
                .target(Redaction.Target.RESULT)
                .redactors(NOTHING);
    }

    @Test
    void appliesToMatchingToolsWhenItsConditionHolds() {
        RedactionRule rule = rule("no-cards")
                .when(invocation -> invocation.arguments().get("id") != null)
                .build();

        assertThat(rule.appliesTo(ToolInvocation.of(
                        "crm_find", JsonObject.builder().put("id", 7).build())))
                .isTrue();
        assertThat(rule.appliesTo(ToolInvocation.of("crm_find", JsonObject.empty())))
                .isFalse();
        assertThat(rule.appliesTo(ToolInvocation.of(
                        "db_query", JsonObject.builder().put("id", 7).build())))
                .isFalse();
    }

    @Test
    void reportsItsIdAndTargets() {
        assertThat(rule("no-cards").build().redaction())
                .isEqualTo(new Redaction("no-cards", Set.of(Redaction.Target.RESULT)));
    }

    @Test
    void rejectsAnIncompleteRule() {
        assertThatThrownBy(() -> rule("bad id").build()).isInstanceOf(InvalidPolicyException.class);
        assertThatThrownBy(() -> RedactionRule.builder("r")
                        .target(Redaction.Target.RESULT)
                        .redactors(NOTHING)
                        .build())
                .hasMessageContaining("at least one tool");
        assertThatThrownBy(() ->
                        RedactionRule.builder("r").tools("*").redactors(NOTHING).build())
                .hasMessageContaining("needs a target");
        assertThatThrownBy(() -> RedactionRule.builder("r")
                        .tools("*")
                        .target(Redaction.Target.RESULT)
                        .build())
                .hasMessageContaining("at least one redactor");
    }

    @Test
    void rejectsArgumentRedactionInsteadOfIgnoringIt() {
        assertThatThrownBy(() -> rule("no-emails-to-search")
                        .target(Redaction.Target.ARGS)
                        .build())
                .isInstanceOf(InvalidPolicyException.class)
                .hasMessageContaining("redacting arguments is not supported yet");
    }
}
