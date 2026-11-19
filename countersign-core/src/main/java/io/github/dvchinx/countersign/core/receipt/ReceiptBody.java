package io.github.dvchinx.countersign.core.receipt;

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
 */
record ReceiptBody(
        String streamId,
        long sequence,
        String invocationId,
        String tool,
        String argsSha256,
        Decision.Type decision,
        @Nullable String reason,
        EvaluationResult.Basis basis,
        List<String> matchedPolicies,
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
                receipt.argsSha256(),
                receipt.decision(),
                receipt.reason(),
                receipt.basis(),
                receipt.matchedPolicies(),
                receipt.principal(),
                receipt.conversationId(),
                receipt.timestamp(),
                receipt.previousHash());
    }

    /** The canonical JSON of this body. Field names are part of the receipt format (ADR 0003). */
    String canonicalJson() {
        JsonObject json = JsonObject.builder()
                .put("streamId", streamId)
                .put("sequence", JsonNumber.of(sequence))
                .put("invocationId", invocationId)
                .put("tool", tool)
                .put("argsSha256", argsSha256)
                .put("decision", decision.name())
                .put("reason", nullable(reason))
                .put("basis", basis.name())
                .put(
                        "matchedPolicies",
                        new JsonArray(matchedPolicies.stream()
                                .<JsonValue>map(JsonString::of)
                                .toList()))
                .put("principal", nullable(principal))
                .put("conversationId", nullable(conversationId))
                .put("timestamp", TIMESTAMP.format(timestamp))
                .put("previousHash", previousHash)
                .build();
        return CanonicalJson.write(json);
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
                argsSha256,
                decision,
                reason,
                basis,
                matchedPolicies,
                principal,
                conversationId,
                timestamp,
                previousHash,
                hash,
                signature);
    }

    private static JsonValue nullable(@Nullable String value) {
        return value == null ? JsonNull.INSTANCE : JsonString.of(value);
    }
}
