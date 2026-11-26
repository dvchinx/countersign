package io.github.dvchinx.countersign.core.receipt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dvchinx.countersign.core.model.ApprovalOutcome;
import io.github.dvchinx.countersign.core.model.Decision;
import io.github.dvchinx.countersign.core.model.EvaluationResult;
import io.github.dvchinx.countersign.core.model.JsonObject;
import io.github.dvchinx.countersign.core.model.Receipt;
import io.github.dvchinx.countersign.core.model.ToolInvocation;
import io.github.dvchinx.countersign.core.receipt.VerificationResult.Invalid;
import io.github.dvchinx.countersign.core.receipt.VerificationResult.Problem;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Receipts written by 0.1.0 must keep verifying (ADR 0003, ADR 0015).
 *
 * <p>The two receipts below were created and signed by Countersign 0.1.0, with a fixed key and clock, and are copied
 * here byte for byte. Every later change to the receipt format must leave them valid.
 */
class ReceiptFormatCompatibilityTest {

    /** The Ed25519 key that signed the receipts: seed 0x01..0x20. Test-only, never used anywhere else. */
    private static final String SEED_HEX = "0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20";

    private static final String PUBLIC_KEY = "MCowBQYDK2VwAyEAebVWLo/mVPlAeLES6KmLp5AfhTrmlb7X4OORC60ElmQ=";
    private static final Instant TIME = Instant.parse("2026-11-24T10:00:00.123Z");
    private static final Clock CLOCK = Clock.fixed(TIME, ZoneOffset.UTC);

    private static final Receipt APPROVED_TRANSFER = new Receipt(
            "stream-0.1.0",
            1,
            "inv-1",
            "payments.transfer",
            "5593c2750bb6c8fd1d0cbcf62495ab51479bfe37e96387f77fe341dee0626677",
            Decision.Type.REQUIRE_APPROVAL,
            "Moves real money",
            EvaluationResult.Basis.POLICY_MATCHED,
            List.of("transfers-need-approval"),
            ApprovalOutcome.APPROVED,
            "bob",
            "alice",
            "conv-7",
            TIME,
            Receipt.GENESIS_HASH,
            "ad504f68c1d86b562ee67283cc63574a885da073fde103ac2f7365c468da5045",
            "kX5x6f5pfMWvFvBU7cg068pW0JQszRKyFWDZgqKNBLQuDeL8/BcdDs1GPa6fU4ANK4JBT+SQmvv0PEiSYMyaBw==");

    private static final Receipt DEFAULT_ALLOW = new Receipt(
            "stream-0.1.0",
            2,
            "inv-2",
            "accounts.balance",
            "22f8eea909400af98adf3681a9f31923ef6b7fcba4abb553d92823a3e9d5c25e",
            Decision.Type.ALLOW,
            null,
            EvaluationResult.Basis.DEFAULT_DECISION,
            List.of(),
            null,
            null,
            null,
            null,
            TIME,
            "ad504f68c1d86b562ee67283cc63574a885da073fde103ac2f7365c468da5045",
            "e98523bdf8ec9b7a864d119f92a722cb55e6bfd3e4209a629b440c52f1785602",
            "CX2aT/kAljp1zo3qJdybs9F+3JIMO77tw1K8+ZkkSHmeqh39k16kCTKrDq1y30W7zL5Q1QByQgHkQg5BYF6XAg==");

    private static ReceiptChain chain() throws Exception {
        byte[] pkcs8 = HexFormat.of().parseHex("302e020100300506032b657004220420" + SEED_HEX);
        PrivateKey key = KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
        return ReceiptChain.start(
                "stream-0.1.0", new Ed25519ReceiptSigner(key), new ArgumentsHmac(new byte[32]), CLOCK);
    }

    private static ToolInvocation transfer() {
        return new ToolInvocation(
                "inv-1",
                "payments.transfer",
                JsonObject.builder().put("amount", 5000).put("to", "ACME").build(),
                "alice",
                "conv-7",
                TIME);
    }

    private static EvaluationResult needsApproval() {
        return EvaluationResult.matched("transfers-need-approval", Decision.requireApproval("Moves real money"));
    }

    @Test
    void receiptsFrom010StillVerify() {
        assertThat(ReceiptVerifier.fromX509Base64(PUBLIC_KEY).verify(List.of(APPROVED_TRANSFER, DEFAULT_ALLOW)))
                .isEqualTo(new VerificationResult.Valid(2));
    }

    @Test
    void aReceiptWithNoRedactionsHashesExactlyAs010Did() throws Exception {
        Receipt receipt = chain().append(transfer(), needsApproval(), ApprovalOutcome.APPROVED, "bob", List.of());

        assertThat(receipt).isEqualTo(APPROVED_TRANSFER);
        assertThat(ReceiptBody.of(receipt).canonicalJson()).doesNotContain("redactions");
    }

    @Test
    void recordsAndSignsRedactionsWhenThereAreSome() throws Exception {
        Receipt receipt = chain().append(
                        transfer(),
                        needsApproval(),
                        ApprovalOutcome.APPROVED,
                        "bob",
                        List.of("no-cards", "no-contact"));

        assertThat(receipt.redactions()).containsExactly("no-cards", "no-contact");
        assertThat(receipt.hash()).isNotEqualTo(APPROVED_TRANSFER.hash());
        assertThat(ReceiptBody.of(receipt).canonicalJson()).contains("\"redactions\":[\"no-cards\",\"no-contact\"]");
        assertThat(ReceiptVerifier.fromX509Base64(PUBLIC_KEY).verify(List.of(receipt)))
                .isEqualTo(new VerificationResult.Valid(1));
    }

    @Test
    void detectsRedactionsRemovedFromAStoredReceipt() throws Exception {
        Receipt redacted =
                chain().append(transfer(), needsApproval(), ApprovalOutcome.APPROVED, "bob", List.of("no-cards"));
        // Dropping the field makes the stored receipt look like one that was never redacted.
        Receipt stripped = new Receipt(
                redacted.streamId(),
                redacted.sequence(),
                redacted.invocationId(),
                redacted.tool(),
                redacted.argsHmac(),
                redacted.decision(),
                redacted.reason(),
                redacted.basis(),
                redacted.matchedPolicies(),
                redacted.approval(),
                redacted.approver(),
                redacted.principal(),
                redacted.conversationId(),
                redacted.timestamp(),
                redacted.previousHash(),
                redacted.hash(),
                redacted.signature());

        assertThat(ReceiptVerifier.fromX509Base64(PUBLIC_KEY).verify(List.of(stripped)))
                .isEqualTo(new Invalid(0, 1, Problem.HASH_MISMATCH));
    }

    @Test
    void neverRecordsRedactionsForACallThatDoesNotRun() throws Exception {
        ReceiptChain chain = chain();

        assertThatThrownBy(() -> chain.append(
                        transfer(),
                        EvaluationResult.matched("no", Decision.deny("No")),
                        null,
                        null,
                        List.of("no-cards")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                        chain.append(transfer(), needsApproval(), ApprovalOutcome.REJECTED, "bob", List.of("no-cards")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(chain.append(transfer(), needsApproval(), ApprovalOutcome.APPROVED, "bob", List.of())
                        .sequence())
                .as("a rejected append does not advance the chain")
                .isEqualTo(1);
    }
}
