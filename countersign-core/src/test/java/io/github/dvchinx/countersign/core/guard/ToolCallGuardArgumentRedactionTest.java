package io.github.dvchinx.countersign.core.guard;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dvchinx.countersign.core.model.ApprovalDecision;
import io.github.dvchinx.countersign.core.model.ApprovalRequest;
import io.github.dvchinx.countersign.core.model.Decision;
import io.github.dvchinx.countersign.core.model.DecisionEvent;
import io.github.dvchinx.countersign.core.model.EvaluationResult.Basis;
import io.github.dvchinx.countersign.core.model.JsonObject;
import io.github.dvchinx.countersign.core.model.JsonString;
import io.github.dvchinx.countersign.core.model.Receipt;
import io.github.dvchinx.countersign.core.model.Redaction;
import io.github.dvchinx.countersign.core.model.ToolInvocation;
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
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/** Redaction rules that target the arguments: what the tool receives, decided before it runs (ADR 0015). */
class ToolCallGuardArgumentRedactionTest {

    private static final KeyPair KEYS = keys();
    private static final ArgumentsHmac ARGS_HMAC = new ArgumentsHmac(new byte[32]);

    private static final PolicyEngine ENGINE = new PolicyEngine(
            List.of(
                    Policy.builder("searches")
                            .tools("web_search")
                            .decision(Decision.allow())
                            .build(),
                    Policy.builder("emails-need-approval")
                            .tools("email_send")
                            .decision(Decision.requireApproval("Writes to a customer"))
                            .build()),
            Decision.deny("No policy allows this call"));

    private static final RedactionRule NO_EMAILS_TO_SEARCH = RedactionRule.builder("no-emails-to-search")
            .tools("web_search", "email_send")
            .target(Redaction.Target.ARGS)
            .redactors(PatternRedactor.of("[\\w.+-]+@[\\w-]+(?:\\.[\\w-]+)+", "[EMAIL]"))
            .build();

    private static final JsonObject SEARCH = JsonObject.builder()
            .put("query", "orders of ana@example.com")
            .put("limit", 5)
            .build();

    private final List<Receipt> stored = new ArrayList<>();
    private final List<DecisionEvent> events = new ArrayList<>();
    private final List<ApprovalRequest> approvals = new ArrayList<>();

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
                                "stream-1", new Ed25519ReceiptSigner(KEYS.getPrivate()), ARGS_HMAC, Clock.systemUTC()),
                        stored::add)
                .listener(events::add)
                .approvals(approving());
    }

    /** Approves every request, remembering what the approver was shown. */
    private ApprovalGateway approving() {
        return request -> {
            approvals.add(request);
            return CompletableFuture.completedFuture(new ApprovalDecision.Approved("bob"));
        };
    }

    private static ToolInvocation call(String tool, JsonObject arguments) {
        return ToolInvocation.of(tool, arguments);
    }

    @Test
    void handsTheToolRedactedArgumentsAndRecordsTheProposedOnes() {
        Verdict.Proceed proceed = (Verdict.Proceed)
                builder().redaction(NO_EMAILS_TO_SEARCH).build().check(call("web_search", SEARCH));

        assertThat(proceed.redactedArguments())
                .isEqualTo(JsonObject.builder()
                        .put("query", "orders of [EMAIL]")
                        .put("limit", 5)
                        .build());
        // The receipt's hash is of what the model proposed and the policies judged (ADR 0004, ADR 0015).
        assertThat(proceed.receipt().argsHmac()).isEqualTo(ARGS_HMAC.hex(SEARCH));
        assertThat(proceed.receipt().redactions()).containsExactly("no-emails-to-search");
        assertThat(proceed.resultRedaction().isEmpty()).isTrue();
    }

    @Test
    void showsTheApproverTheProposedArgumentsAndRedactsTheApprovedCall() {
        JsonObject email = JsonObject.builder()
                .put("to", "ana@example.com")
                .put("body", "Your order shipped")
                .build();

        Verdict.Proceed proceed = (Verdict.Proceed)
                builder().redaction(NO_EMAILS_TO_SEARCH).build().check(call("email_send", email));

        assertThat(approvals)
                .singleElement()
                .satisfies(
                        request -> assertThat(request.invocation().arguments()).isEqualTo(email));
        assertThat(proceed.redactedArguments().get("to")).isEqualTo(JsonString.of("[EMAIL]"));
        assertThat(proceed.receipt().approver()).isEqualTo("bob");
    }

    @Test
    void leavesTheArgumentsAloneWhenNoRuleChangesThem() {
        RedactionRule onlyResults = RedactionRule.builder("only-results")
                .tools("web_search")
                .target(Redaction.Target.RESULT)
                .redactors(PatternRedactor.of("x"))
                .build();
        JsonObject noEmail = JsonObject.builder().put("query", "weather").build();
        ToolCallGuard guard =
                builder().redaction(onlyResults).redaction(NO_EMAILS_TO_SEARCH).build();

        Verdict.Proceed proceed = (Verdict.Proceed) guard.check(call("web_search", noEmail));

        assertThat(proceed.redactedArguments())
                .as("pass the original input unchanged")
                .isNull();
        assertThat(proceed.receipt().redactions()).containsExactly("only-results", "no-emails-to-search");
    }

    @Test
    void appliesArgumentRulesInOrder() {
        RedactionRule second = RedactionRule.builder("then-shout")
                .tools("web_search")
                .target(Redaction.Target.ARGS)
                .redactors(PatternRedactor.of("\\[EMAIL\\]", "[HIDDEN]"))
                .build();

        Verdict.Proceed proceed = (Verdict.Proceed) builder()
                .redaction(NO_EMAILS_TO_SEARCH)
                .redaction(second)
                .build()
                .check(call("web_search", SEARCH));

        assertThat(proceed.redactedArguments().get("query")).isEqualTo(JsonString.of("orders of [HIDDEN]"));
    }

    @Test
    void redactsArgumentsAndResultWithOneRule() {
        RedactionRule both = RedactionRule.builder("no-contact")
                .tools("web_search")
                .target(Redaction.Target.ARGS)
                .target(Redaction.Target.RESULT)
                .redactors(FieldRedactor.of(List.of("query")))
                .build();

        Verdict.Proceed proceed =
                (Verdict.Proceed) builder().redaction(both).build().check(call("web_search", SEARCH));

        assertThat(proceed.redactedArguments().get("query")).isEqualTo(JsonString.of("[REDACTED]"));
        assertThat(proceed.resultRedaction().ruleIds()).containsExactly("no-contact");
        assertThat(events)
                .singleElement()
                .satisfies(event -> assertThat(event.redactions())
                        .containsExactly(
                                new Redaction("no-contact", Set.of(Redaction.Target.ARGS, Redaction.Target.RESULT))));
    }

    @Test
    void refusesTheCallWhenAnArgumentRedactorFailsBeforeAskingForApproval() {
        RedactionRule failing = RedactionRule.builder("broken-detector")
                .tools("email_send")
                .target(Redaction.Target.ARGS)
                .redactors(value -> {
                    throw new IllegalStateException("detector crashed on ana@example.com");
                })
                .build();

        Verdict verdict = builder()
                .redaction(failing)
                .build()
                .check(call(
                        "email_send",
                        JsonObject.builder().put("to", "ana@example.com").build()));

        assertThat(verdict).isInstanceOfSatisfying(Verdict.Refuse.class, refuse -> {
            assertThat(refuse.decision()).isEqualTo(Decision.Type.DENY);
            assertThat(refuse.reason()).doesNotContain("ana@example.com", "detector");
        });
        assertThat(approvals).isEmpty();
        assertThat(stored).singleElement().satisfies(receipt -> {
            assertThat(receipt.basis()).isEqualTo(Basis.EVALUATION_FAILED);
            assertThat(receipt.matchedPolicies()).containsExactly("broken-detector");
            assertThat(receipt.redactions()).isEmpty();
        });
    }

    @Test
    void refusesTheCallWhenAnArgumentRedactorDoesNotReturnAnObject() {
        RedactionRule flattening = RedactionRule.builder("flattening")
                .tools("web_search")
                .target(Redaction.Target.ARGS)
                .redactors(value -> JsonString.of("[REDACTED]"))
                .build();

        Verdict verdict = builder().redaction(flattening).build().check(call("web_search", SEARCH));

        assertThat(verdict).isInstanceOf(Verdict.Refuse.class);
        assertThat(stored.getFirst().matchedPolicies()).containsExactly("flattening");
    }

    @Test
    void neverRedactsArgumentsOfACallThatDoesNotRun() {
        Verdict verdict = builder()
                .redaction(NO_EMAILS_TO_SEARCH)
                .build()
                .check(call(
                        "db_query",
                        JsonObject.builder().put("sql", "ana@example.com").build()));

        assertThat(verdict).isInstanceOf(Verdict.Refuse.class);
        assertThat(stored.getFirst().redactions()).isEmpty();
    }

    @Test
    void receiptsOfCallsWithRedactedArgumentsVerify() {
        ToolCallGuard guard = builder().redaction(NO_EMAILS_TO_SEARCH).build();
        guard.check(call("web_search", SEARCH));
        guard.check(call(
                "email_send", JsonObject.builder().put("to", "ana@example.com").build()));

        assertThat(new ReceiptVerifier(KEYS.getPublic()).verify(stored).isValid())
                .isTrue();
    }
}
