package io.github.dvchinx.countersign.core.guard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dvchinx.countersign.core.model.ApprovalDecision;
import io.github.dvchinx.countersign.core.model.ApprovalOutcome;
import io.github.dvchinx.countersign.core.model.Decision;
import io.github.dvchinx.countersign.core.model.DecisionEvent;
import io.github.dvchinx.countersign.core.model.EvaluationResult.Basis;
import io.github.dvchinx.countersign.core.model.JsonObject;
import io.github.dvchinx.countersign.core.model.JsonString;
import io.github.dvchinx.countersign.core.model.JsonValue;
import io.github.dvchinx.countersign.core.model.Receipt;
import io.github.dvchinx.countersign.core.model.Redaction;
import io.github.dvchinx.countersign.core.model.ToolInvocation;
import io.github.dvchinx.countersign.core.policy.InvalidPolicyException;
import io.github.dvchinx.countersign.core.policy.Policy;
import io.github.dvchinx.countersign.core.policy.PolicyEngine;
import io.github.dvchinx.countersign.core.receipt.ArgumentsHmac;
import io.github.dvchinx.countersign.core.receipt.Ed25519ReceiptSigner;
import io.github.dvchinx.countersign.core.receipt.ReceiptChain;
import io.github.dvchinx.countersign.core.receipt.ReceiptVerifier;
import io.github.dvchinx.countersign.core.redaction.FieldRedactor;
import io.github.dvchinx.countersign.core.redaction.PatternRedactor;
import io.github.dvchinx.countersign.core.redaction.RedactionRule;
import io.github.dvchinx.countersign.core.spi.ApprovalGateway;
import io.github.dvchinx.countersign.core.spi.DecisionListener;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Redaction rules on the decision path: an obligation on every call that runs, never a decision (ADR 0015). */
class ToolCallGuardRedactionTest {

    private static final KeyPair KEYS = keys();

    private static final PolicyEngine ENGINE = new PolicyEngine(
            List.of(
                    Policy.builder("crm-reads")
                            .tools("crm_*")
                            .decision(Decision.allow())
                            .build(),
                    Policy.builder("exports-need-approval")
                            .tools("export_*")
                            .decision(Decision.requireApproval("Sends data out"))
                            .build(),
                    Policy.builder("no-deletes")
                            .tools("delete_*")
                            .decision(Decision.deny("No deletes"))
                            .build()),
            Decision.deny("No policy allows this call"));

    private static final RedactionRule NO_CARDS = RedactionRule.builder("no-cards")
            .tools("crm_*", "export_*", "delete_*")
            .target(Redaction.Target.RESULT)
            .redactors(PatternRedactor.of("\\b\\d{16}\\b", "[CARD]"))
            .build();

    private static final RedactionRule NO_CONTACT = RedactionRule.builder("no-contact")
            .tools("crm_*", "export_*")
            .target(Redaction.Target.RESULT)
            .redactors(FieldRedactor.of(List.of("email")))
            .build();

    private static final JsonObject CUSTOMER = JsonObject.builder()
            .put("name", "Ana")
            .put("email", "ana@example.com")
            .put("card", "4111111111111111")
            .build();

    private final List<Receipt> stored = new ArrayList<>();
    private final List<DecisionEvent> events = new ArrayList<>();
    private final List<String> withheld = new ArrayList<>();

    private static KeyPair keys() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private ToolCallGuard.Builder builder() {
        return ToolCallGuard.builder(
                        ENGINE,
                        ReceiptChain.start(
                                "stream-1",
                                new Ed25519ReceiptSigner(KEYS.getPrivate()),
                                new ArgumentsHmac(new byte[32]),
                                Clock.systemUTC()),
                        stored::add)
                .listener(new DecisionListener() {
                    @Override
                    public void onDecision(DecisionEvent event) {
                        events.add(event);
                    }

                    @Override
                    public void onResultWithheld(Receipt receipt, String ruleId) {
                        withheld.add(receipt.invocationId() + ":" + ruleId);
                    }
                });
    }

    private ToolCallGuard guard() {
        return builder().redaction(NO_CARDS).redaction(NO_CONTACT).build();
    }

    private static ToolInvocation call(String tool) {
        return ToolInvocation.of(tool, JsonObject.builder().put("id", 7).build());
    }

    private static ApprovalGateway answering(ApprovalDecision decision) {
        return request -> CompletableFuture.completedFuture(decision);
    }

    @Test
    void appliesEveryMatchingRuleToTheResultInOrder() {
        Verdict.Proceed proceed = (Verdict.Proceed) guard().check(call("crm_find_customer"));

        JsonValue redacted = proceed.resultRedaction().apply(CUSTOMER);

        assertThat(redacted)
                .isEqualTo(JsonObject.builder()
                        .put("name", "Ana")
                        .put("email", "[REDACTED]")
                        .put("card", "[CARD]")
                        .build());
        assertThat(proceed.resultRedaction().ruleIds()).containsExactly("no-cards", "no-contact");
        assertThat(proceed.receipt().redactions()).containsExactly("no-cards", "no-contact");
        assertThat(proceed.receipt().decision()).isEqualTo(Decision.Type.ALLOW);
        assertThat(proceed.receipt().matchedPolicies()).containsExactly("crm-reads");
    }

    @Test
    void reportsTheRedactionsToListeners() {
        guard().check(call("crm_find_customer"));

        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.decision()).isEqualTo(Decision.Type.ALLOW);
            assertThat(event.redactions())
                    .containsExactly(
                            new Redaction("no-cards", Set.of(Redaction.Target.RESULT)),
                            new Redaction("no-contact", Set.of(Redaction.Target.RESULT)));
        });
    }

    @Test
    void redactsTheResultOfAnApprovedCall() {
        // The case that rules out REDACT as a decision: approval is stricter, and would have dropped the redaction.
        Verdict.Proceed proceed = (Verdict.Proceed) builder()
                .redaction(NO_CARDS)
                .approvals(answering(new ApprovalDecision.Approved("bob")))
                .build()
                .check(call("export_customers"));

        assertThat(proceed.resultRedaction().apply(JsonString.of("card 4111111111111111")))
                .isEqualTo(JsonString.of("card [CARD]"));
        assertThat(proceed.receipt().approval()).isEqualTo(ApprovalOutcome.APPROVED);
        assertThat(proceed.receipt().redactions()).containsExactly("no-cards");
    }

    @Test
    void recordsNoRedactionsForACallThatDoesNotRun() {
        ToolCallGuard guard = builder()
                .redaction(NO_CARDS)
                .approvals(answering(new ApprovalDecision.Rejected("bob", "No")))
                .build();

        assertThat(guard.check(call("delete_customer"))).isInstanceOf(Verdict.Refuse.class);
        assertThat(guard.check(call("export_customers"))).isInstanceOf(Verdict.Refuse.class);

        assertThat(stored).extracting(Receipt::redactions).containsOnly(List.of());
        assertThat(events).allSatisfy(event -> assertThat(event.redactions()).isEmpty());
    }

    @Test
    void leavesTheResultAloneWhenNoRuleMatches() {
        RedactionRule onlyForVips = RedactionRule.builder("vips")
                .tools("crm_*")
                .when(invocation -> invocation.arguments().get("vip") != null)
                .target(Redaction.Target.RESULT)
                .redactors(PatternRedactor.of("Ana"))
                .build();

        Verdict.Proceed proceed =
                (Verdict.Proceed) builder().redaction(onlyForVips).build().check(call("crm_find_customer"));

        assertThat(proceed.resultRedaction().isEmpty()).isTrue();
        assertThat(proceed.resultRedaction().apply(CUSTOMER)).isEqualTo(CUSTOMER);
        assertThat(proceed.receipt().redactions()).isEmpty();
    }

    @Test
    void refusesTheCallWhenARuleConditionFailsBeforeAskingForApproval() {
        AtomicInteger asked = new AtomicInteger();
        RedactionRule broken = RedactionRule.builder("broken")
                .tools("export_*")
                .when(invocation -> {
                    throw new IllegalStateException("bad condition");
                })
                .target(Redaction.Target.RESULT)
                .redactors(PatternRedactor.of("x"))
                .build();

        Verdict verdict = builder()
                .redaction(broken)
                .approvals(request -> {
                    asked.incrementAndGet();
                    return CompletableFuture.completedFuture(new ApprovalDecision.Approved("bob"));
                })
                .build()
                .check(call("export_customers"));

        assertThat(verdict).isInstanceOfSatisfying(Verdict.Refuse.class, refuse -> {
            assertThat(refuse.decision()).isEqualTo(Decision.Type.DENY);
            assertThat(refuse.reason()).doesNotContain("bad condition");
        });
        assertThat(asked).hasValue(0);
        assertThat(stored).singleElement().satisfies(receipt -> {
            assertThat(receipt.basis()).isEqualTo(Basis.EVALUATION_FAILED);
            assertThat(receipt.matchedPolicies()).containsExactly("broken");
            assertThat(receipt.redactions()).isEmpty();
        });
    }

    @Test
    void withholdsTheResultWhenARedactorFails() {
        RedactionRule failing = RedactionRule.builder("failing")
                .tools("crm_*")
                .target(Redaction.Target.RESULT)
                .redactors(value -> {
                    throw new IllegalStateException("detector crashed on 4111111111111111");
                })
                .build();
        Verdict.Proceed proceed =
                (Verdict.Proceed) builder().redaction(failing).build().check(call("crm_find_customer"));

        assertThatThrownBy(() -> proceed.resultRedaction().apply(CUSTOMER))
                .isInstanceOfSatisfying(
                        ResultWithheldException.class,
                        e -> assertThat(e.ruleId()).isEqualTo("failing"))
                .hasNoCause()
                .hasMessageNotContaining("4111");
        assertThat(withheld).containsExactly(proceed.receipt().invocationId() + ":failing");
    }

    @Test
    void withholdsTheResultWhenARedactorReturnsNull() {
        RedactionRule nullRule = RedactionRule.builder("null-rule")
                .tools("crm_*")
                .target(Redaction.Target.RESULT)
                .redactors(value -> null)
                .build();
        Verdict.Proceed proceed =
                (Verdict.Proceed) builder().redaction(nullRule).build().check(call("crm_find_customer"));

        assertThatThrownBy(() -> proceed.resultRedaction().apply(CUSTOMER)).isInstanceOf(ResultWithheldException.class);
    }

    @Test
    void stillWithholdsTheResultWhenAListenerFails() {
        RedactionRule failing = RedactionRule.builder("failing")
                .tools("crm_*")
                .target(Redaction.Target.RESULT)
                .redactors(value -> {
                    throw new IllegalStateException("boom");
                })
                .build();
        DecisionListener broken = new DecisionListener() {
            @Override
            public void onDecision(DecisionEvent event) {}

            @Override
            public void onResultWithheld(Receipt receipt, String ruleId) {
                throw new IllegalStateException("metrics down");
            }
        };
        Verdict.Proceed proceed = (Verdict.Proceed)
                builder().listener(broken).redaction(failing).build().check(call("crm_find_customer"));

        assertThatThrownBy(() -> proceed.resultRedaction().apply(CUSTOMER)).isInstanceOf(ResultWithheldException.class);
        assertThat(withheld).hasSize(1);
    }

    @Test
    void receiptsWithRedactionsVerify() {
        ToolCallGuard guard = guard();
        guard.check(call("crm_find_customer"));
        guard.check(call("delete_customer"));
        guard.check(call("crm_list"));

        assertThat(new ReceiptVerifier(KEYS.getPublic()).verify(stored).isValid())
                .isTrue();
    }

    @Test
    void rejectsRuleIdsThatAreNotUnique() {
        assertThatThrownBy(
                        () -> builder().redaction(NO_CARDS).redaction(NO_CARDS).build())
                .isInstanceOf(InvalidPolicyException.class)
                .hasMessageContaining("Duplicate redaction rule id: 'no-cards'");
        RedactionRule clash = RedactionRule.builder("crm-reads")
                .tools("crm_*")
                .target(Redaction.Target.RESULT)
                .redactors(PatternRedactor.of("x"))
                .build();
        assertThatThrownBy(() -> builder().redaction(clash).build())
                .isInstanceOf(InvalidPolicyException.class)
                .hasMessageContaining("already a policy id");
    }
}
