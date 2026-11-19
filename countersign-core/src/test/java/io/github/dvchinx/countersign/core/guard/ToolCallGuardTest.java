package io.github.dvchinx.countersign.core.guard;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dvchinx.countersign.core.model.Decision;
import io.github.dvchinx.countersign.core.model.EvaluationResult.Basis;
import io.github.dvchinx.countersign.core.model.JsonNumber;
import io.github.dvchinx.countersign.core.model.JsonObject;
import io.github.dvchinx.countersign.core.model.Receipt;
import io.github.dvchinx.countersign.core.model.ToolInvocation;
import io.github.dvchinx.countersign.core.policy.Policy;
import io.github.dvchinx.countersign.core.policy.PolicyEngine;
import io.github.dvchinx.countersign.core.receipt.ArgumentsHmac;
import io.github.dvchinx.countersign.core.receipt.Ed25519ReceiptSigner;
import io.github.dvchinx.countersign.core.receipt.ReceiptChain;
import io.github.dvchinx.countersign.core.receipt.ReceiptVerifier;
import io.github.dvchinx.countersign.core.spi.AuditSink;
import io.github.dvchinx.countersign.core.spi.ReceiptSigner;
import java.math.BigDecimal;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ToolCallGuardTest {

    private static final KeyPair KEYS = keys();
    private static final ArgumentsHmac ARGS_HMAC = new ArgumentsHmac(new byte[32]);

    private final List<Receipt> stored = new ArrayList<>();
    private final AuditSink sink = stored::add;
    private final AuditSink brokenSink = receipt -> {
        throw new IllegalStateException("database down");
    };

    private static final PolicyEngine ENGINE = new PolicyEngine(
            List.of(
                    Policy.builder("transfers-need-approval")
                            .tools("payments.transfer")
                            .when(invocation ->
                                    ((JsonNumber) invocation.arguments().get("amount"))
                                                    .value()
                                                    .compareTo(BigDecimal.valueOf(1000))
                                            > 0)
                            .decision(Decision.requireApproval("Moves real money"))
                            .build(),
                    Policy.builder("payments-allowed")
                            .tools("payments.*")
                            .decision(Decision.allow())
                            .build(),
                    Policy.builder("broken")
                            .tools("broken.*")
                            .when(invocation -> {
                                throw new IllegalStateException("bug");
                            })
                            .decision(Decision.allow())
                            .build()),
            Decision.deny("No policy allows this call"));

    private static ReceiptChain chain(ReceiptSigner signer) {
        return ReceiptChain.start("stream-1", signer, ARGS_HMAC, Clock.systemUTC());
    }

    private static ReceiptSigner signer() {
        return new Ed25519ReceiptSigner(KEYS.getPrivate());
    }

    private ToolCallGuard guard() {
        return ToolCallGuard.builder(ENGINE, chain(signer()), sink)
                .strictAudit(false)
                .build();
    }

    private static ToolInvocation call(String tool, long amount) {
        return ToolInvocation.of(
                tool, JsonObject.builder().put("amount", amount).build());
    }

    @Test
    void allowsTheCallAndRecordsAReceiptBeforeItRuns() {
        Verdict verdict = guard().check(call("payments.transfer", 50));

        assertThat(verdict).isInstanceOf(Verdict.Proceed.class);
        Receipt receipt = ((Verdict.Proceed) verdict).receipt();
        assertThat(receipt.decision()).isEqualTo(Decision.Type.ALLOW);
        assertThat(stored).containsExactly(receipt);
    }

    @Test
    void refusesWhenNoPolicyAllowsTheCall() {
        Verdict verdict = guard().check(call("db.drop", 1));

        assertThat(verdict).isInstanceOfSatisfying(Verdict.Refuse.class, refuse -> {
            assertThat(refuse.decision()).isEqualTo(Decision.Type.DENY);
            assertThat(refuse.reason()).isEqualTo("No policy allows this call");
            assertThat(refuse.receipt().basis()).isEqualTo(Basis.DEFAULT_DECISION);
        });
        assertThat(stored).hasSize(1);
    }

    @Test
    void refusesTransferAboveLimitWhenNoApproverIsAvailable() {
        Verdict verdict = guard().check(call("payments.transfer", 5000));

        assertThat(verdict).isInstanceOfSatisfying(Verdict.Refuse.class, refuse -> {
            assertThat(refuse.decision()).isEqualTo(Decision.Type.REQUIRE_APPROVAL);
            assertThat(refuse.reason()).contains("Moves real money", "approval");
        });
    }

    @Test
    void refusesAndRecordsWhenAPolicyThrows() {
        Verdict verdict = guard().check(call("broken.tool", 1));

        assertThat(verdict)
                .isInstanceOfSatisfying(
                        Verdict.Refuse.class,
                        refuse -> assertThat(refuse.receipt().basis()).isEqualTo(Basis.EVALUATION_FAILED));
    }

    @Test
    void refusesAndRecordsInvalidArguments() {
        Verdict verdict = guard().refuseInvalidArguments("payments.transfer", "inv-9", "alice", null, Instant.now());

        assertThat(verdict).isInstanceOfSatisfying(Verdict.Refuse.class, refuse -> {
            assertThat(refuse.receipt().basis()).isEqualTo(Basis.INVALID_ARGUMENTS);
            assertThat(refuse.receipt().invocationId()).isEqualTo("inv-9");
            assertThat(refuse.receipt().principal()).isEqualTo("alice");
        });
    }

    @Test
    void refusesWithoutReceiptWhenSigningFails() {
        ToolCallGuard guard = ToolCallGuard.builder(
                        ENGINE,
                        chain(hash -> {
                            throw new IllegalStateException("HSM unreachable");
                        }),
                        sink)
                .strictAudit(false)
                .build();

        Verdict verdict = guard.check(call("payments.transfer", 50));

        assertThat(verdict).isInstanceOfSatisfying(Verdict.Refuse.class, refuse -> {
            assertThat(refuse.receipt()).isNull();
            assertThat(refuse.reason()).doesNotContain("HSM");
        });
        assertThat(stored).isEmpty();
    }

    @Test
    void letsTheDecisionStandWhenTheAuditSinkFailsAndAuditIsNotStrict() {
        ToolCallGuard guard = ToolCallGuard.builder(ENGINE, chain(signer()), brokenSink)
                .strictAudit(false)
                .build();

        assertThat(guard.check(call("payments.transfer", 50))).isInstanceOf(Verdict.Proceed.class);
    }

    @Test
    void refusesWhenTheAuditSinkFailsAndAuditIsStrict() {
        ToolCallGuard guard = ToolCallGuard.builder(ENGINE, chain(signer()), brokenSink)
                .strictAudit(true)
                .build();

        Verdict verdict = guard.check(call("payments.transfer", 50));

        assertThat(verdict).isInstanceOfSatisfying(Verdict.Refuse.class, refuse -> {
            assertThat(refuse.receipt()).isNotNull();
            assertThat(refuse.reason()).doesNotContain("database");
        });
    }

    @Test
    void storedReceiptsFormAVerifiableChain() {
        ToolCallGuard guard = guard();
        guard.check(call("payments.transfer", 50));
        guard.check(call("payments.transfer", 5000));
        guard.check(call("db.drop", 1));

        assertThat(new ReceiptVerifier(KEYS.getPublic()).verify(stored).isValid())
                .isTrue();
    }

    private static KeyPair keys() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
