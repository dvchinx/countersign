package io.github.dvchinx.countersign.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dvchinx.countersign.core.approval.InMemoryApprovalGateway;
import io.github.dvchinx.countersign.core.audit.InMemoryAuditSink;
import io.github.dvchinx.countersign.core.guard.ToolCallGuard;
import io.github.dvchinx.countersign.core.guard.Verdict;
import io.github.dvchinx.countersign.core.model.ApprovalOutcome;
import io.github.dvchinx.countersign.core.model.Decision;
import io.github.dvchinx.countersign.core.model.JsonObject;
import io.github.dvchinx.countersign.core.model.Receipt;
import io.github.dvchinx.countersign.core.model.ToolInvocation;
import io.github.dvchinx.countersign.core.policy.InvalidPolicyException;
import io.github.dvchinx.countersign.core.policy.Policy;
import io.github.dvchinx.countersign.core.policy.PolicyEngine;
import io.github.dvchinx.countersign.core.receipt.ArgumentsHmac;
import io.github.dvchinx.countersign.core.receipt.ReceiptException;
import io.github.dvchinx.countersign.core.receipt.ReceiptVerifier;
import io.github.dvchinx.countersign.core.spi.AuditSink;
import io.github.dvchinx.countersign.springai.CountersignToolCallingBeanPostProcessor;
import io.github.dvchinx.countersign.springai.GuardedToolCallingManager;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.chat.client.autoconfigure.ChatClientAutoConfiguration;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class CountersignAutoConfigurationTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(CountersignAutoConfiguration.class));

    /** The outermost policy error, the one the failure analyzer reports. */
    private static InvalidPolicyException policyError(Throwable startupFailure) {
        for (Throwable t = startupFailure; t != null; t = t.getCause()) {
            if (t instanceof InvalidPolicyException policyException) {
                return policyException;
            }
        }
        throw new AssertionError("No InvalidPolicyException in " + startupFailure);
    }

    private static ToolInvocation call(String tool) {
        return ToolInvocation.of(tool, JsonObject.builder().put("amount", 5).build());
    }

    @Test
    void deniesEveryCallWhenNoPolicyIsConfiguredAndSaysSoAtStartup(CapturedOutput output) {
        runner.run(context -> {
            Verdict verdict = context.getBean(ToolCallGuard.class).check(call("anything"));

            assertThat(verdict).isInstanceOfSatisfying(Verdict.Refuse.class, refuse -> {
                assertThat(refuse.decision()).isEqualTo(Decision.Type.DENY);
                assertThat(refuse.reason()).isEqualTo("No policy allows this call");
            });
            assertThat(output)
                    .contains("Countersign is active: 0 policies (0 from @RequiresApproval), default decision DENY");
        });
    }

    @Test
    void warnsWhenKeysAreGeneratedAndReceiptsAreOnlyInMemory(CapturedOutput output) {
        runner.run(context -> assertThat(output)
                .contains("countersign.receipts.signing-key is not set")
                .contains("countersign.receipts.args-hmac-key is not set")
                .contains("receipts in memory only"));
    }

    @Test
    void buildsPoliciesFromPropertiesWithDefaultReasons() {
        runner.withPropertyValues(
                        "countersign.policies[0].id=transfers-need-approval",
                        "countersign.policies[0].tools[0]=payments.transfer",
                        "countersign.policies[0].when=#args['amount'] > 1000",
                        "countersign.policies[0].decision=require-approval",
                        "countersign.policies[1].id=payments",
                        "countersign.policies[1].tools=payments.*",
                        "countersign.policies[1].decision=deny",
                        "countersign.policies[1].priority=-1")
                .run(context -> {
                    PolicyEngine engine = context.getBean(PolicyEngine.class);

                    assertThat(engine.policies())
                            .extracting(Policy::id)
                            .containsExactly("payments", "transfers-need-approval");
                    assertThat(engine.policies().getFirst().decision())
                            .isEqualTo(Decision.deny("Blocked by policy 'payments'"));
                    assertThat(engine.policies().getLast().decision())
                            .isEqualTo(Decision.requireApproval(
                                    "Policy 'transfers-need-approval' requires human approval"));
                });
    }

    @Test
    void usesConfiguredKeysSoReceiptsCanBeVerifiedLater(CapturedOutput output) throws Exception {
        KeyPair keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] hmacKey = new byte[32];
        hmacKey[0] = 7;

        runner.withPropertyValues(
                        "countersign.receipts.signing-key="
                                + Base64.getEncoder()
                                        .encodeToString(keys.getPrivate().getEncoded()),
                        "countersign.receipts.args-hmac-key="
                                + Base64.getEncoder().encodeToString(hmacKey),
                        "countersign.receipts.stream-id=bank-eu-1")
                .run(context -> {
                    ToolInvocation invocation = call("x");
                    context.getBean(ToolCallGuard.class).check(invocation);
                    List<Receipt> receipts =
                            context.getBean(InMemoryAuditSink.class).receipts();

                    assertThat(new ReceiptVerifier(keys.getPublic())
                                    .verify(receipts)
                                    .isValid())
                            .isTrue();
                    assertThat(receipts.getFirst().streamId()).isEqualTo("bank-eu-1");
                    assertThat(receipts.getFirst().argsHmac())
                            .isEqualTo(new ArgumentsHmac(hmacKey).hex(invocation.arguments()));
                    assertThat(output).doesNotContain("is not set");
                });
    }

    @Test
    void namesTheDefaultStreamAfterTheApplication() {
        runner.withPropertyValues("spring.application.name=banking-agent").run(context -> {
            context.getBean(ToolCallGuard.class).check(call("x"));

            assertThat(context.getBean(InMemoryAuditSink.class)
                            .receipts()
                            .getFirst()
                            .streamId())
                    .matches("banking-agent-[0-9a-f]{16}");
        });
    }

    @Test
    void refusesToStartWithAPolicyThatHasNoTools() {
        runner.withPropertyValues("countersign.policies[0].id=empty", "countersign.policies[0].decision=deny")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(policyError(context.getStartupFailure()))
                            .hasMessageContaining("countersign.policies[0]")
                            .hasMessageContaining("empty");
                });
    }

    @Test
    void refusesToStartWithAnInvalidCondition() {
        runner.withPropertyValues(
                        "countersign.policies[0].id=broken",
                        "countersign.policies[0].tools=db.*",
                        "countersign.policies[0].when=#args['sql'] matches",
                        "countersign.policies[0].decision=deny")
                .run(context -> assertThat(policyError(context.getStartupFailure()))
                        .hasMessageContaining("countersign.policies[0]")
                        .hasMessageContaining("SpEL"));
    }

    @Test
    void refusesToStartWithAPolicyWithoutDecision() {
        runner.withPropertyValues("countersign.policies[0].id=undecided", "countersign.policies[0].tools=db.*")
                .run(context ->
                        assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("decision"));
    }

    @Test
    void refusesToStartWithAnInvalidSigningKeyWithoutPrintingIt() {
        runner.withPropertyValues("countersign.receipts.signing-key=c2VjcmV0LWJ1dC13cm9uZw==")
                .run(context -> assertThat(context.getStartupFailure())
                        .rootCause()
                        .isInstanceOf(ReceiptException.class)
                        .message()
                        .doesNotContain("c2VjcmV0LWJ1dC13cm9uZw=="));
    }

    @Test
    void explainsConfigurationErrorsWithoutAStackTrace() {
        CountersignFailureAnalyzer analyzer = new CountersignFailureAnalyzer();

        FailureAnalysis policy = analyzer.analyze(new IllegalStateException(
                "wrapper", new InvalidPolicyException("countersign.policies[0]: 'id' is required")));
        FailureAnalysis key = analyzer.analyze(new ReceiptException("bad key"));

        assertThat(policy.getDescription()).contains("countersign.policies[0]");
        assertThat(policy.getAction()).contains("decision");
        assertThat(key.getAction()).contains("countersign.receipts");
    }

    @Test
    void addsPolicyBeansToConfiguredPolicies() {
        runner.withPropertyValues(
                        "countersign.policies[0].id=from-yaml",
                        "countersign.policies[0].tools=a.*",
                        "countersign.policies[0].decision=allow")
                .withBean(
                        Policy.class,
                        () -> Policy.builder("from-code")
                                .tools("b.*")
                                .decision(Decision.allow())
                                .build())
                .run(context -> assertThat(context.getBean(PolicyEngine.class).policies())
                        .extracting(Policy::id)
                        .containsExactly("from-yaml", "from-code"));
    }

    @Test
    void usesTheApplicationsOwnAuditSink() {
        List<Receipt> stored = new CopyOnWriteArrayList<>();
        runner.withBean(AuditSink.class, () -> stored::add).run(context -> {
            context.getBean(ToolCallGuard.class).check(call("x"));

            assertThat(context).doesNotHaveBean(InMemoryAuditSink.class);
            assertThat(stored).hasSize(1);
        });
    }

    @Test
    void refusesCallsWhenStrictAuditCannotStoreTheReceipt() {
        runner.withPropertyValues(
                        "countersign.audit.strict=true",
                        "countersign.policies[0].id=all",
                        "countersign.policies[0].tools=*",
                        "countersign.policies[0].decision=allow")
                .withBean(AuditSink.class, () -> receipt -> {
                    throw new IllegalStateException("database down");
                })
                .run(context -> assertThat(context.getBean(ToolCallGuard.class).check(call("x")))
                        .isInstanceOf(Verdict.Refuse.class));
    }

    @Test
    void asksTheApplicationsApprovalGatewayWithTheConfiguredTimeout(CapturedOutput output) {
        InMemoryApprovalGateway approvals = new InMemoryApprovalGateway();
        runner.withPropertyValues(
                        "countersign.approval.timeout=50ms",
                        "countersign.policies[0].id=approve-all",
                        "countersign.policies[0].tools=*",
                        "countersign.policies[0].decision=require-approval")
                .withBean(InMemoryApprovalGateway.class, () -> approvals)
                .run(context -> {
                    Verdict verdict = context.getBean(ToolCallGuard.class).check(call("x"));

                    assertThat(verdict)
                            .isInstanceOfSatisfying(
                                    Verdict.Refuse.class,
                                    refuse -> assertThat(refuse.receipt().approval())
                                            .isEqualTo(ApprovalOutcome.TIMED_OUT));
                    assertThat(output).contains("approvals InMemoryApprovalGateway (timeout PT0.05S)");
                    assertThat(output).doesNotContain("no ApprovalGateway bean is defined");
                });
    }

    @Test
    void warnsWhenCallsNeedApprovalButNobodyCanGiveIt(CapturedOutput output) {
        runner.withPropertyValues(
                        "countersign.policies[0].id=approve-all",
                        "countersign.policies[0].tools=*",
                        "countersign.policies[0].decision=require-approval")
                .run(context -> {
                    assertThat(output).contains("approvals none").contains("no ApprovalGateway bean is defined");
                    assertThat(((Verdict.Refuse)
                                            context.getBean(ToolCallGuard.class).check(call("x")))
                                    .receipt()
                                    .approval())
                            .isEqualTo(ApprovalOutcome.UNAVAILABLE);
                });
    }

    @Test
    void warnsWhenTheDefaultDecisionDoesNotDeny(CapturedOutput output) {
        runner.withPropertyValues("countersign.default-decision=allow")
                .run(context -> assertThat(output)
                        .contains("default decision ALLOW")
                        .contains("tool calls that no policy matches are not denied"));
    }

    @Test
    void guardsNothingAndSaysSoWhenDisabled(CapturedOutput output) {
        runner.withPropertyValues("countersign.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(ToolCallGuard.class);
            assertThat(context).doesNotHaveBean(CountersignToolCallingBeanPostProcessor.class);
            assertThat(output).contains("tool calls are NOT guarded");
        });
    }

    @Test
    void wrapsSpringAisToolCallingManager() {
        runner.withConfiguration(
                        AutoConfigurations.of(ToolCallingAutoConfiguration.class, ChatClientAutoConfiguration.class))
                .withBean(ChatModel.class, () -> new ScriptedChatModel("x", "{}"))
                .run(context -> assertThat(context.getBean(ToolCallingManager.class))
                        .isInstanceOf(GuardedToolCallingManager.class));
    }
}
