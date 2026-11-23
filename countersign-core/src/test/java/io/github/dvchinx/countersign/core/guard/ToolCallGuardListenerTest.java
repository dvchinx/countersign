package io.github.dvchinx.countersign.core.guard;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dvchinx.countersign.core.approval.InMemoryApprovalGateway;
import io.github.dvchinx.countersign.core.model.ApprovalOutcome;
import io.github.dvchinx.countersign.core.model.Decision;
import io.github.dvchinx.countersign.core.model.DecisionEvent;
import io.github.dvchinx.countersign.core.model.EvaluationResult.Basis;
import io.github.dvchinx.countersign.core.model.JsonObject;
import io.github.dvchinx.countersign.core.model.Receipt;
import io.github.dvchinx.countersign.core.model.ToolInvocation;
import io.github.dvchinx.countersign.core.policy.Policy;
import io.github.dvchinx.countersign.core.policy.PolicyEngine;
import io.github.dvchinx.countersign.core.receipt.ArgumentsHmac;
import io.github.dvchinx.countersign.core.receipt.Ed25519ReceiptSigner;
import io.github.dvchinx.countersign.core.receipt.ReceiptChain;
import io.github.dvchinx.countersign.core.spi.AuditSink;
import io.github.dvchinx.countersign.core.spi.DecisionListener;
import io.github.dvchinx.countersign.core.spi.ReceiptSigner;
import java.security.GeneralSecurityException;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ToolCallGuardListenerTest {

    private static final ReceiptSigner SIGNER = signer();

    private static final PolicyEngine ENGINE = new PolicyEngine(
            List.of(
                    Policy.builder("balances")
                            .tools("accounts.balance")
                            .decision(Decision.allow())
                            .build(),
                    Policy.builder("slow")
                            .tools("slow.tool")
                            .when(invocation -> {
                                sleep(Duration.ofMillis(30));
                                return true;
                            })
                            .decision(Decision.allow())
                            .build(),
                    Policy.builder("transfers-need-approval")
                            .tools("payments.transfer")
                            .decision(Decision.requireApproval("Moves real money"))
                            .build()),
            Decision.deny("No policy allows this call"));

    private final List<DecisionEvent> events = new ArrayList<>();
    private final List<Receipt> stored = new ArrayList<>();

    private static ReceiptSigner signer() {
        try {
            return new Ed25519ReceiptSigner(
                    KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPrivate());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static ReceiptChain chain(ReceiptSigner signer) {
        return ReceiptChain.start("stream-1", signer, new ArgumentsHmac(new byte[32]), Clock.systemUTC());
    }

    private ToolCallGuard.Builder guard(ReceiptSigner signer, AuditSink sink) {
        return ToolCallGuard.builder(ENGINE, chain(signer), sink).listener(events::add);
    }

    private ToolCallGuard guard() {
        return guard(SIGNER, stored::add).build();
    }

    private static ToolInvocation call(String tool) {
        return ToolInvocation.of(tool, JsonObject.builder().put("amount", 5).build());
    }

    @Test
    void reportsAnAllowedCallWithItsReceipt() {
        Verdict verdict = guard().check(call("accounts.balance"));

        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.tool()).isEqualTo("accounts.balance");
            assertThat(event.decision()).isEqualTo(Decision.Type.ALLOW);
            assertThat(event.basis()).isEqualTo(Basis.POLICY_MATCHED);
            assertThat(event.policyId()).isEqualTo("balances");
            assertThat(event.approval()).isNull();
            assertThat(event.proceeds()).isTrue();
            assertThat(event.receipt()).isEqualTo(((Verdict.Proceed) verdict).receipt());
            assertThat(event.receiptRecorded()).isTrue();
            assertThat(event.auditFailed()).isFalse();
            assertThat(event.evaluationTime()).isGreaterThanOrEqualTo(Duration.ZERO);
        });
    }

    @Test
    void reportsADenialByTheDefaultDecision() {
        guard().check(call("db.query"));

        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.decision()).isEqualTo(Decision.Type.DENY);
            assertThat(event.basis()).isEqualTo(Basis.DEFAULT_DECISION);
            assertThat(event.policyId()).isNull();
            assertThat(event.proceeds()).isFalse();
            assertThat(event.receipt()).isEqualTo(stored.getFirst());
        });
    }

    @Test
    void reportsTheApprovalOutcome() {
        guard(SIGNER, stored::add)
                .approvals(new InMemoryApprovalGateway())
                .approvalTimeout(Duration.ofMillis(20))
                .build()
                .check(call("payments.transfer"));

        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.decision()).isEqualTo(Decision.Type.REQUIRE_APPROVAL);
            assertThat(event.approval()).isEqualTo(ApprovalOutcome.TIMED_OUT);
            assertThat(event.proceeds()).isFalse();
        });
    }

    @Test
    void measuresPolicyEvaluationButNotTheWaitForApproval() {
        guard().check(call("slow.tool"));
        guard(SIGNER, stored::add)
                .approvals(new InMemoryApprovalGateway())
                .approvalTimeout(Duration.ofMillis(200))
                .build()
                .check(call("payments.transfer"));

        assertThat(events.get(0).evaluationTime()).isGreaterThanOrEqualTo(Duration.ofMillis(30));
        assertThat(events.get(1).evaluationTime()).isLessThan(Duration.ofMillis(200));
    }

    @Test
    void reportsUnreadableArgumentsWithoutEvaluationTime() {
        guard().refuseInvalidArguments("accounts.balance", "inv-1", null, null, Instant.now());

        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.basis()).isEqualTo(Basis.INVALID_ARGUMENTS);
            assertThat(event.evaluationTime()).isZero();
            assertThat(event.proceeds()).isFalse();
        });
    }

    @Test
    void reportsAReceiptThatCouldNotBeSigned() {
        ReceiptSigner broken = hash -> {
            throw new IllegalStateException("HSM down");
        };

        guard(broken, stored::add).build().check(call("accounts.balance"));

        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.decision()).isEqualTo(Decision.Type.ALLOW);
            assertThat(event.receiptRecorded()).isFalse();
            assertThat(event.proceeds()).isFalse();
            assertThat(event.auditFailed()).isFalse();
        });
    }

    @Test
    void reportsAnAuditFailureThatLetsTheCallProceedWhenAuditIsNotStrict() {
        AuditSink down = receipt -> {
            throw new IllegalStateException("database down");
        };

        guard(SIGNER, down).build().check(call("accounts.balance"));

        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.auditFailed()).isTrue();
            assertThat(event.proceeds()).isTrue();
        });
    }

    @Test
    void reportsAnAuditFailureThatRefusesTheCallWhenAuditIsStrict() {
        AuditSink down = receipt -> {
            throw new IllegalStateException("database down");
        };

        guard(SIGNER, down).strictAudit(true).build().check(call("accounts.balance"));

        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.auditFailed()).isTrue();
            assertThat(event.proceeds()).isFalse();
        });
    }

    @Test
    void keepsTheVerdictAndCallsTheOtherListenersWhenOneThrows() {
        DecisionListener broken = event -> {
            throw new IllegalStateException("metrics backend down");
        };
        List<DecisionEvent> later = new ArrayList<>();
        ToolCallGuard guard = ToolCallGuard.builder(ENGINE, chain(SIGNER), stored::add)
                .listener(broken)
                .listener(later::add)
                .build();

        assertThat(guard.check(call("accounts.balance"))).isInstanceOf(Verdict.Proceed.class);
        assertThat(guard.check(call("db.query"))).isInstanceOf(Verdict.Refuse.class);
        assertThat(later).extracting(DecisionEvent::proceeds).containsExactly(true, false);
    }
}
