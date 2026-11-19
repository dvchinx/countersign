package io.github.dvchinx.countersign.core.receipt;

import io.github.dvchinx.countersign.core.model.Decision;
import io.github.dvchinx.countersign.core.model.EvaluationResult;
import io.github.dvchinx.countersign.core.model.JsonObject;
import io.github.dvchinx.countersign.core.model.ToolInvocation;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

/** Shared keys, clock and invocations for receipt tests. */
final class ReceiptFixtures {

    static final KeyPair KEYS = generateKeys();
    static final KeyPair OTHER_KEYS = generateKeys();
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-18T14:02:11.482917Z"), ZoneOffset.UTC);

    private ReceiptFixtures() {}

    static ReceiptChain newChain() {
        return ReceiptChain.start("stream-1", new Ed25519ReceiptSigner(KEYS.getPrivate()), CLOCK);
    }

    static ReceiptVerifier verifier() {
        return new ReceiptVerifier(KEYS.getPublic());
    }

    static ToolInvocation transfer(String id, long amount) {
        return new ToolInvocation(
                id,
                "payments.transfer",
                JsonObject.builder().put("amount", amount).put("to", "ACME").build(),
                "alice",
                "conv-7",
                CLOCK.instant());
    }

    static EvaluationResult needsApproval() {
        return EvaluationResult.matched("transfers-need-approval", Decision.requireApproval("Moves real money"));
    }

    static KeyPair generateKeys() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
