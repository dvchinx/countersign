package io.github.dvchinx.countersign.core.guard;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dvchinx.countersign.core.model.ApprovalDecision;
import io.github.dvchinx.countersign.core.model.ApprovalOutcome;
import io.github.dvchinx.countersign.core.model.ApprovalRequest;
import io.github.dvchinx.countersign.core.model.Decision;
import io.github.dvchinx.countersign.core.model.JsonObject;
import io.github.dvchinx.countersign.core.model.Receipt;
import io.github.dvchinx.countersign.core.model.ToolInvocation;
import io.github.dvchinx.countersign.core.policy.Policy;
import io.github.dvchinx.countersign.core.policy.PolicyEngine;
import io.github.dvchinx.countersign.core.receipt.ArgumentsHmac;
import io.github.dvchinx.countersign.core.receipt.Ed25519ReceiptSigner;
import io.github.dvchinx.countersign.core.receipt.ReceiptChain;
import io.github.dvchinx.countersign.core.receipt.ReceiptVerifier;
import io.github.dvchinx.countersign.core.spi.ApprovalGateway;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ToolCallGuardApprovalTest {

    private static final KeyPair KEYS = keys();
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-19T10:00:00Z"), ZoneOffset.UTC);
    private static final PolicyEngine ENGINE = new PolicyEngine(
            List.of(Policy.builder("transfers-need-approval")
                    .tools("payments.transfer")
                    .decision(Decision.requireApproval("Moves real money"))
                    .build()),
            Decision.deny("No policy allows this call"));

    private final List<Receipt> stored = new ArrayList<>();

    private ToolCallGuard guard(ApprovalGateway gateway, Duration timeout) {
        return ToolCallGuard.builder(
                        ENGINE,
                        ReceiptChain.start(
                                "s",
                                new Ed25519ReceiptSigner(KEYS.getPrivate()),
                                new ArgumentsHmac(new byte[32]),
                                CLOCK),
                        stored::add)
                .approvals(gateway)
                .approvalTimeout(timeout)
                .clock(CLOCK)
                .build();
    }

    private ToolCallGuard guard(ApprovalGateway gateway) {
        return guard(gateway, Duration.ofSeconds(5));
    }

    private static ToolInvocation transfer() {
        return ToolInvocation.of(
                "payments.transfer",
                JsonObject.builder().put("to", "ACME").put("amount", 5000).build());
    }

    private static Verdict.Refuse refused(Verdict verdict) {
        assertThat(verdict).isInstanceOf(Verdict.Refuse.class);
        return (Verdict.Refuse) verdict;
    }

    @Test
    void runsTransferAboveLimitOnceAHumanApprovesIt() {
        Verdict verdict = guard(request -> CompletableFuture.completedFuture(ApprovalDecision.approved("bob")))
                .check(transfer());

        assertThat(verdict).isInstanceOfSatisfying(Verdict.Proceed.class, proceed -> {
            assertThat(proceed.receipt().decision()).isEqualTo(Decision.Type.REQUIRE_APPROVAL);
            assertThat(proceed.receipt().approval()).isEqualTo(ApprovalOutcome.APPROVED);
            assertThat(proceed.receipt().approver()).isEqualTo("bob");
        });
    }

    @Test
    void showsTheApproverTheCallItsPolicyAndTheDeadline() {
        AtomicReference<ApprovalRequest> asked = new AtomicReference<>();
        ToolInvocation invocation = transfer();

        guard(request -> {
                    asked.set(request);
                    return CompletableFuture.completedFuture(ApprovalDecision.approved("bob"));
                })
                .check(invocation);

        assertThat(asked.get().invocation()).isEqualTo(invocation);
        assertThat(asked.get().policyId()).isEqualTo("transfers-need-approval");
        assertThat(asked.get().reason()).isEqualTo("Moves real money");
        assertThat(asked.get().deadline()).isEqualTo(CLOCK.instant().plusSeconds(5));
    }

    @Test
    void deniesTransferWhenTheApproverRejectsIt() {
        Verdict.Refuse refuse = refused(
                guard(request -> CompletableFuture.completedFuture(ApprovalDecision.rejected("bob", "Unknown payee")))
                        .check(transfer()));

        assertThat(refuse.decision()).isEqualTo(Decision.Type.REQUIRE_APPROVAL);
        assertThat(refuse.reason()).isEqualTo("The call was rejected by bob: Unknown payee");
        assertThat(refuse.receipt().approval()).isEqualTo(ApprovalOutcome.REJECTED);
        assertThat(refuse.receipt().approver()).isEqualTo("bob");
    }

    @Test
    void deniesTransferWhenApprovalTimesOutAndWithdrawsTheRequest() {
        CompletableFuture<ApprovalDecision> neverAnswered = new CompletableFuture<>();

        Verdict.Refuse refuse =
                refused(guard(request -> neverAnswered, Duration.ofMillis(50)).check(transfer()));

        assertThat(refuse.reason()).contains("no answer arrived in time");
        assertThat(refuse.receipt().approval()).isEqualTo(ApprovalOutcome.TIMED_OUT);
        assertThat(refuse.receipt().approver()).isNull();
        assertThat(neverAnswered).isCancelled();
    }

    @Test
    void deniesTransferWhenNoApproverIsConfigured() {
        ToolCallGuard guard = ToolCallGuard.builder(
                        ENGINE,
                        ReceiptChain.start(
                                "s",
                                new Ed25519ReceiptSigner(KEYS.getPrivate()),
                                new ArgumentsHmac(new byte[32]),
                                CLOCK),
                        stored::add)
                .build();

        Verdict.Refuse refuse = refused(guard.check(transfer()));

        assertThat(refuse.reason()).contains("no approver is available");
        assertThat(refuse.receipt().approval()).isEqualTo(ApprovalOutcome.UNAVAILABLE);
    }

    @Test
    void deniesTransferWhenTheGatewayThrows() {
        Verdict.Refuse refuse = refused(guard(request -> {
                    throw new IllegalStateException("Slack is down");
                })
                .check(transfer()));

        assertThat(refuse.receipt().approval()).isEqualTo(ApprovalOutcome.UNAVAILABLE);
        assertThat(refuse.reason()).doesNotContain("Slack");
    }

    @Test
    void deniesTransferWhenTheGatewayFailsLater() {
        Verdict.Refuse refuse =
                refused(guard(request -> CompletableFuture.failedFuture(new IllegalStateException("Slack is down")))
                        .check(transfer()));

        assertThat(refuse.receipt().approval()).isEqualTo(ApprovalOutcome.UNAVAILABLE);
    }

    @Test
    void deniesTransferWhenTheGatewayAnswersNull() {
        Verdict.Refuse refuse = refused(
                guard(request -> CompletableFuture.completedFuture(null)).check(transfer()));

        assertThat(refuse.receipt().approval()).isEqualTo(ApprovalOutcome.UNAVAILABLE);
    }

    @Test
    void deniesTransferAndKeepsTheInterruptWhenTheWaitingThreadIsInterrupted() {
        CompletableFuture<ApprovalDecision> neverAnswered = new CompletableFuture<>();
        Thread.currentThread().interrupt();
        try {
            Verdict.Refuse refuse = refused(guard(request -> neverAnswered).check(transfer()));

            assertThat(refuse.receipt().approval()).isEqualTo(ApprovalOutcome.UNAVAILABLE);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(neverAnswered).isCancelled();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void doesNotAskForApprovalWhenNoPolicyRequiresIt() {
        AtomicReference<ApprovalRequest> asked = new AtomicReference<>();
        guard(request -> {
                    asked.set(request);
                    return new CompletableFuture<>();
                })
                .check(ToolInvocation.of("db.query", JsonObject.empty()));

        assertThat(asked.get()).isNull();
        assertThat(stored.getFirst().approval()).isNull();
    }

    @Test
    void approvalOutcomesAreSignedLikeEveryOtherField() {
        guard(request -> CompletableFuture.completedFuture(ApprovalDecision.approved("bob")))
                .check(transfer());

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
