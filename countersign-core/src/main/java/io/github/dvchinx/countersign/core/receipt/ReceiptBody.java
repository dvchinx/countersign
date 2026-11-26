package io.github.dvchinx.countersign.core.receipt;

import io.github.dvchinx.countersign.core.model.ApprovalOutcome;
import io.github.dvchinx.countersign.core.model.Decision;
import io.github.dvchinx.countersign.core.model.EvaluationResult;
import io.github.dvchinx.countersign.core.model.JsonArray;
import io.github.dvchinx.countersign.core.model.JsonNull;
import io.github.dvchinx.countersign.core.model.JsonNumber;
import io.github.dvchinx.countersign.core.model.JsonObject;
import io.github.dvchinx.countersign.core.model.JsonString;
import io.github.dvchinx.countersign.core.model.JsonValue;
import io.github.dvchinx.countersign.core.model.Receipt;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The hashed part of a receipt: every field except {@code hash} and {@code signature}. Shared by
 * {@link ReceiptChain}, which creates receipts, and {@link ReceiptVerifier}, which recomputes their hashes, so the
 * two can never disagree on the format.
 *
 * <p>Format rule since 0.1.0 (ADR 0015): a field added after 0.1.0 is left out of the hashed JSON when it is empty.
 * A receipt that does not use a new field therefore hashes exactly as it did in 0.1.0, and every 0.1.0 receipt keeps
 * verifying with no version field. Fields that existed in 0.1.0 keep their rule: {@code null} is written, not omitted.
 */
record ReceiptBody(
        String streamId,
        long sequence,
        String invocationId,
        String tool,
        String argsHmac,
        Decision.Type decision,
        @Nullable String reason,
        EvaluationResult.Basis basis,
        List<String> matchedPolicies,
        List<String> redactions,
        @Nullable ApprovalOutcome approval,
        @Nullable String approver,
        @Nullable String principal,
        @Nullable String conversationId,
        Instant timestamp,
        String previousHash) {

    // Always three fraction digits, so the text does not depend on whether the milliseconds happen to be zero.
    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    static ReceiptBody of(Receipt receipt) {
        return new ReceiptBody(
                receipt.streamId(),
                receipt.sequence(),
                receipt.invocationId(),
                receipt.tool(),
                receipt.argsHmac(),
                receipt.decision(),
                receipt.reason(),
                receipt.basis(),
                receipt.matchedPolicies(),
                receipt.redactions(),
                receipt.approval(),
                receipt.approver(),
                receipt.principal(),
                receipt.conversationId(),
                receipt.timestamp(),
                receipt.previousHash());
    }

    /** The canonical JSON of this body. Field names are part of the receipt format (ADR 0003). */
    String canonicalJson() {
        JsonObject.Builder json = JsonObject.builder()
                .put("streamId", streamId)
                .put("sequence", JsonNumber.of(sequence))
                .put("invocationId", invocationId)
                .put("tool", tool)
                .put("argsHmac", argsHmac)
                .put("decision", decision.name())
                .put("reason", nullable(reason))
                .put("basis", basis.name())
                .put("matchedPolicies", strings(matchedPolicies))
                .put("approval", approval == null ? JsonNull.INSTANCE : JsonString.of(approval.name()))
                .put("approver", nullable(approver))
                .put("principal", nullable(principal))
                .put("conversationId", nullable(conversationId))
                .put("timestamp", TIMESTAMP.format(timestamp))
                .put("previousHash", previousHash);
        // Added after 0.1.0: written only when not empty (see the class comment).
        if (!redactions.isEmpty()) {
            json.put("redactions", strings(redactions));
        }
        return CanonicalJson.write(json.build());
    }

    /** The SHA-256 of the canonical JSON. */
    byte[] sha256() {
        return CanonicalJson.sha256(canonicalJson().getBytes(StandardCharsets.UTF_8));
    }

    Receipt seal(String hash, String signature) {
        return new Receipt(
                streamId,
                sequence,
                invocationId,
                tool,
                argsHmac,
                decision,
                reason,
                basis,
                matchedPolicies,
                redactions,
                approval,
                approver,
                principal,
                conversationId,
                timestamp,
                previousHash,
                hash,
                signature);
    }

    private static JsonArray strings(List<String> values) {
        return new JsonArray(values.stream().<JsonValue>map(JsonString::of).toList());
    }

    private static JsonValue nullable(@Nullable String value) {
        return value == null ? JsonNull.INSTANCE : JsonString.of(value);
    }
}
