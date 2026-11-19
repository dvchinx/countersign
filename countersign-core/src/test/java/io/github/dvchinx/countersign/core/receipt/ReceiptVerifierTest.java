package io.github.dvchinx.countersign.core.receipt;

import static io.github.dvchinx.countersign.core.receipt.ReceiptFixtures.CLOCK;
import static io.github.dvchinx.countersign.core.receipt.ReceiptFixtures.OTHER_KEYS;
import static io.github.dvchinx.countersign.core.receipt.ReceiptFixtures.needsApproval;
import static io.github.dvchinx.countersign.core.receipt.ReceiptFixtures.newChain;
import static io.github.dvchinx.countersign.core.receipt.ReceiptFixtures.transfer;
import static io.github.dvchinx.countersign.core.receipt.ReceiptFixtures.verifier;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dvchinx.countersign.core.model.ApprovalOutcome;
import io.github.dvchinx.countersign.core.model.Decision;
import io.github.dvchinx.countersign.core.model.EvaluationResult;
import io.github.dvchinx.countersign.core.model.Receipt;
import io.github.dvchinx.countersign.core.receipt.VerificationResult.Invalid;
import io.github.dvchinx.countersign.core.receipt.VerificationResult.Problem;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class ReceiptVerifierTest {

    private static List<Receipt> stream(int size) {
        ReceiptChain chain = newChain();
        List<Receipt> receipts = new ArrayList<>();
        for (int i = 1; i <= size; i++) {
            // Approved receipts, so every field, approval and approver included, has a value to tamper with.
            receipts.add(
                    chain.append(transfer("inv-" + i, i * 1000L), needsApproval(), ApprovalOutcome.APPROVED, "bob"));
        }
        return receipts;
    }

    @Test
    void acceptsAnUntouchedStream() {
        assertThat(verifier().verify(stream(3))).isEqualTo(new VerificationResult.Valid(3));
        assertThat(verifier().verify(List.of())).isEqualTo(new VerificationResult.Valid(0));
    }

    static Stream<String> receiptFields() {
        return Stream.of(Receipt.class.getRecordComponents()).map(RecordComponent::getName);
    }

    @ParameterizedTest
    @MethodSource("receiptFields")
    void detectsAnEditToAnyFieldOfAStoredReceipt(String field) {
        List<Receipt> receipts = new ArrayList<>(stream(3));
        receipts.set(1, tamper(receipts.get(1), field));

        VerificationResult result = verifier().verify(receipts);

        assertThat(result).isInstanceOf(Invalid.class);
        assertThat(((Invalid) result).index()).isEqualTo(1);
    }

    @Test
    void detectsAnEditToTheLastReceiptEvenIfOnlyTheHashCaseChanges() {
        List<Receipt> receipts = new ArrayList<>(stream(2));
        Receipt last = receipts.get(1);
        receipts.set(1, with(last, "hash", last.hash().toUpperCase(Locale.ROOT)));

        assertThat(verifier().verify(receipts)).isEqualTo(new Invalid(1, 2, Problem.HASH_MISMATCH));
    }

    @Test
    void detectsAForgedReceiptWhoseHashWasRecomputedWithoutTheKey() {
        List<Receipt> receipts = new ArrayList<>(stream(2));
        Receipt original = receipts.get(1);
        Receipt edited = with(original, "decision", Decision.Type.ALLOW);
        String recomputed = HexFormat.of().formatHex(ReceiptBody.of(edited).sha256());
        receipts.set(1, with(edited, "hash", recomputed));

        assertThat(verifier().verify(receipts)).isEqualTo(new Invalid(1, 2, Problem.BAD_SIGNATURE));
    }

    @Test
    void detectsADeletedReceipt() {
        List<Receipt> receipts = new ArrayList<>(stream(3));
        receipts.remove(1);

        assertThat(verifier().verify(receipts)).isEqualTo(new Invalid(1, 3, Problem.SEQUENCE_GAP));
    }

    @Test
    void detectsReorderedReceipts() {
        List<Receipt> receipts = stream(3);

        assertThat(verifier().verify(List.of(receipts.get(0), receipts.get(2), receipts.get(1))))
                .isEqualTo(new Invalid(1, 3, Problem.SEQUENCE_GAP));
    }

    @Test
    void detectsAReceiptSplicedInFromAnotherStream() {
        List<Receipt> receipts = new ArrayList<>(stream(2));
        Receipt foreign = ReceiptChain.start(
                        "stream-2",
                        new Ed25519ReceiptSigner(ReceiptFixtures.KEYS.getPrivate()),
                        ReceiptFixtures.ARGS_HMAC,
                        CLOCK)
                .append(transfer("x", 1), needsApproval());
        receipts.add(foreign);

        assertThat(verifier().verify(receipts)).isEqualTo(new Invalid(2, 1, Problem.STREAM_MISMATCH));
    }

    @Test
    void detectsAChainRebuiltFromTheMiddleWithTheSameSequence() {
        List<Receipt> original = stream(2);
        // Same stream id, a valid signature and the right sequence, but linked to a different first receipt.
        ReceiptChain rogue = newChain();
        rogue.append(transfer("rogue-1", 1), EvaluationResult.matched("ok", Decision.allow()));
        Receipt rogueSecond = rogue.append(transfer("rogue-2", 2), needsApproval());

        assertThat(verifier().verify(List.of(original.get(0), rogueSecond)))
                .isEqualTo(new Invalid(1, 2, Problem.CHAIN_BROKEN));
    }

    @Test
    void rejectsReceiptsSignedWithAnotherKey() {
        assertThat(new ReceiptVerifier(OTHER_KEYS.getPublic()).verify(stream(1)))
                .isEqualTo(new Invalid(0, 1, Problem.BAD_SIGNATURE));
    }

    @Test
    void readsTheVerificationKeyFromBase64() {
        String encoded = Base64.getEncoder()
                .encodeToString(ReceiptFixtures.KEYS.getPublic().getEncoded());

        assertThat(ReceiptVerifier.fromX509Base64(encoded).verify(stream(2)).isValid())
                .isTrue();
        assertThatThrownBy(() -> ReceiptVerifier.fromX509Base64("not a key")).isInstanceOf(ReceiptException.class);
    }

    private static Receipt tamper(Receipt receipt, String field) {
        Object value = read(receipt, field);
        Object changed = switch (value) {
            case null -> "x";
            case String s -> s + "x";
            case Long l -> l + 1;
            case Instant instant -> instant.plusMillis(1);
            case ApprovalOutcome outcome ->
                outcome == ApprovalOutcome.APPROVED ? ApprovalOutcome.REJECTED : ApprovalOutcome.APPROVED;
            case Decision.Type type -> type == Decision.Type.ALLOW ? Decision.Type.DENY : Decision.Type.ALLOW;
            case EvaluationResult.Basis basis ->
                basis == EvaluationResult.Basis.POLICY_MATCHED
                        ? EvaluationResult.Basis.DEFAULT_DECISION
                        : EvaluationResult.Basis.POLICY_MATCHED;
            case List<?> list ->
                Stream.concat(list.stream(), Stream.of("extra-policy")).toList();
            default -> throw new IllegalArgumentException("No tampering rule for " + value.getClass());
        };
        return with(receipt, field, changed);
    }

    private static Object read(Receipt receipt, String field) {
        try {
            return Receipt.class.getMethod(field).invoke(receipt);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Receipt with(Receipt receipt, String field, Object value) {
        RecordComponent[] components = Receipt.class.getRecordComponents();
        Object[] values = new Object[components.length];
        Class<?>[] types = new Class<?>[components.length];
        for (int i = 0; i < components.length; i++) {
            types[i] = components[i].getType();
            values[i] = components[i].getName().equals(field) ? value : read(receipt, components[i].getName());
        }
        try {
            return Receipt.class.getDeclaredConstructor(types).newInstance(values);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
