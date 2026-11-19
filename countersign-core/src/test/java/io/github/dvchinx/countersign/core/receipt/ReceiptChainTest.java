package io.github.dvchinx.countersign.core.receipt;

import static io.github.dvchinx.countersign.core.receipt.ReceiptFixtures.ARGS_HMAC;
import static io.github.dvchinx.countersign.core.receipt.ReceiptFixtures.CLOCK;
import static io.github.dvchinx.countersign.core.receipt.ReceiptFixtures.KEYS;
import static io.github.dvchinx.countersign.core.receipt.ReceiptFixtures.needsApproval;
import static io.github.dvchinx.countersign.core.receipt.ReceiptFixtures.newChain;
import static io.github.dvchinx.countersign.core.receipt.ReceiptFixtures.transfer;
import static io.github.dvchinx.countersign.core.receipt.ReceiptFixtures.verifier;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dvchinx.countersign.core.model.Decision;
import io.github.dvchinx.countersign.core.model.EvaluationResult;
import io.github.dvchinx.countersign.core.model.Receipt;
import io.github.dvchinx.countersign.core.model.ToolInvocation;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class ReceiptChainTest {

    @Test
    void firstReceiptStartsTheStreamAtGenesis() {
        Receipt receipt = newChain().append(transfer("inv-1", 5000), needsApproval());

        assertThat(receipt.streamId()).isEqualTo("stream-1");
        assertThat(receipt.sequence()).isEqualTo(1);
        assertThat(receipt.previousHash()).isEqualTo(Receipt.GENESIS_HASH);
        assertThat(receipt.hash()).hasSize(64).isLowerCase();
        assertThat(receipt.signature()).isNotBlank();
    }

    @Test
    void recordsTheDecisionAndWhoItWasFor() {
        Receipt receipt = newChain().append(transfer("inv-1", 5000), needsApproval());

        assertThat(receipt.invocationId()).isEqualTo("inv-1");
        assertThat(receipt.tool()).isEqualTo("payments.transfer");
        assertThat(receipt.decision()).isEqualTo(Decision.Type.REQUIRE_APPROVAL);
        assertThat(receipt.reason()).isEqualTo("Moves real money");
        assertThat(receipt.basis()).isEqualTo(EvaluationResult.Basis.POLICY_MATCHED);
        assertThat(receipt.matchedPolicies()).containsExactly("transfers-need-approval");
        assertThat(receipt.principal()).isEqualTo("alice");
        assertThat(receipt.conversationId()).isEqualTo("conv-7");
    }

    @Test
    void defaultDecisionHasNoMatchedPoliciesAndAllowHasNoReason() {
        ReceiptChain chain = newChain();

        Receipt denied = chain.append(
                transfer("inv-1", 1), EvaluationResult.defaultDecision(Decision.deny("No policy allows this call")));
        Receipt allowed = chain.append(transfer("inv-2", 1), EvaluationResult.matched("ok", Decision.allow()));

        assertThat(denied.matchedPolicies()).isEmpty();
        assertThat(denied.basis()).isEqualTo(EvaluationResult.Basis.DEFAULT_DECISION);
        assertThat(allowed.reason()).isNull();
    }

    @Test
    void storesAKeyedHashOfTheArgumentsNeverTheValues() {
        ToolInvocation invocation = transfer("inv-1", 987654);

        Receipt receipt = newChain().append(invocation, needsApproval());

        assertThat(receipt.argsHmac()).isEqualTo(ARGS_HMAC.hex(invocation.arguments()));
        assertThat(receipt.toString()).doesNotContain("987654", "ACME");
    }

    @Test
    void linksEachReceiptToThePreviousOne() {
        ReceiptChain chain = newChain();

        Receipt first = chain.append(transfer("inv-1", 1), needsApproval());
        Receipt second = chain.append(transfer("inv-2", 2), needsApproval());

        assertThat(second.sequence()).isEqualTo(2);
        assertThat(second.previousHash()).isEqualTo(first.hash());
        assertThat(verifier().verify(List.of(first, second)).isValid()).isTrue();
    }

    @Test
    void truncatesTimestampsToMillisecondsSoStoredReceiptsStillVerify() {
        Receipt receipt = newChain().append(transfer("inv-1", 1), needsApproval());

        assertThat(receipt.timestamp()).isEqualTo(Instant.parse("2026-11-18T14:02:11.482Z"));
    }

    @Test
    void failsClosedAndDoesNotAdvanceWhenSigningFails() {
        AtomicBoolean signerDown = new AtomicBoolean(false);
        Ed25519ReceiptSigner realSigner = new Ed25519ReceiptSigner(KEYS.getPrivate());
        ReceiptChain chain = ReceiptChain.start(
                "stream-1",
                hash -> {
                    if (signerDown.get()) {
                        throw new IllegalStateException("HSM unreachable");
                    }
                    return realSigner.sign(hash);
                },
                ARGS_HMAC,
                CLOCK);
        Receipt first = chain.append(transfer("inv-1", 1), needsApproval());

        signerDown.set(true);
        assertThatThrownBy(() -> chain.append(transfer("inv-2", 2), needsApproval()))
                .isInstanceOf(ReceiptException.class)
                .hasCauseInstanceOf(IllegalStateException.class);

        signerDown.set(false);
        Receipt next = chain.append(transfer("inv-3", 3), needsApproval());
        assertThat(next.sequence()).isEqualTo(2);
        assertThat(next.previousHash()).isEqualTo(first.hash());
    }

    @Test
    void rejectsASignerThatReturnsNoSignature() {
        ReceiptChain chain = ReceiptChain.start("stream-1", hash -> new byte[0], ARGS_HMAC, CLOCK);

        assertThatThrownBy(() -> chain.append(transfer("inv-1", 1), needsApproval()))
                .isInstanceOf(ReceiptException.class);
    }

    @Test
    void resumesAStreamAfterItsLastReceipt() {
        ReceiptChain chain = newChain();
        Receipt first = chain.append(transfer("inv-1", 1), needsApproval());

        ReceiptChain resumed =
                ReceiptChain.resume(first, new Ed25519ReceiptSigner(KEYS.getPrivate()), ARGS_HMAC, CLOCK);
        Receipt second = resumed.append(transfer("inv-2", 2), needsApproval());

        assertThat(resumed.streamId()).isEqualTo("stream-1");
        assertThat(verifier().verify(List.of(first, second)).isValid()).isTrue();
    }

    @Test
    void keepsAnUnbrokenChainUnderConcurrentAppends() throws Exception {
        ReceiptChain chain = newChain();
        List<Receipt> receipts = Collections.synchronizedList(new ArrayList<>());

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            IntStream.range(0, 500)
                    .forEach(i -> executor.submit(
                            () -> receipts.add(chain.append(transfer("inv-" + i, i), needsApproval()))));
        }

        List<Receipt> stream = receipts.stream()
                .sorted(Comparator.comparingLong(Receipt::sequence))
                .toList();
        assertThat(stream).hasSize(500);
        assertThat(verifier().verify(stream)).isEqualTo(new VerificationResult.Valid(500));
    }

    @Test
    void rejectsABlankStreamId() {
        assertThatThrownBy(() -> ReceiptChain.start(" ", hash -> hash, ARGS_HMAC, CLOCK))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
