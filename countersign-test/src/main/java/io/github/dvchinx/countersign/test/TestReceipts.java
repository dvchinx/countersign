package io.github.dvchinx.countersign.test;

import io.github.dvchinx.countersign.core.model.Decision;
import io.github.dvchinx.countersign.core.model.EvaluationResult;
import io.github.dvchinx.countersign.core.model.JsonObject;
import io.github.dvchinx.countersign.core.model.ToolInvocation;
import io.github.dvchinx.countersign.core.receipt.ArgumentsHmac;
import io.github.dvchinx.countersign.core.receipt.Ed25519ReceiptSigner;
import io.github.dvchinx.countersign.core.receipt.ReceiptChain;
import io.github.dvchinx.countersign.core.receipt.ReceiptVerifier;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Real, signed receipts for tests, without setting up keys.
 *
 * <p>Every chain from {@link #newChain()} is signed with one Ed25519 key generated for the test run, and
 * {@link #verifier()} checks receipts against it. Useful to test an audit sink or anything that reads receipts.
 */
public final class TestReceipts {

    private static final KeyPair KEYS = generateKeys();
    private static final ArgumentsHmac ARGS_HMAC = new ArgumentsHmac(new byte[ArgumentsHmac.MIN_KEY_BYTES]);

    private TestReceipts() {}

    /**
     * Starts a chain on a new, random stream.
     *
     * @return the chain
     */
    public static ReceiptChain newChain() {
        return ReceiptChain.start(
                "test-" + UUID.randomUUID(), new Ed25519ReceiptSigner(KEYS.getPrivate()), ARGS_HMAC, Clock.systemUTC());
    }

    /**
     * Returns a verifier for receipts from {@link #newChain()}.
     *
     * @return the verifier
     */
    public static ReceiptVerifier verifier() {
        return new ReceiptVerifier(KEYS.getPublic());
    }

    /**
     * Returns an invocation with fixed arguments and a random id.
     *
     * @param tool the tool name
     * @param principal who the agent acts for, or {@code null}
     * @param conversationId the conversation, or {@code null}
     * @return the invocation
     */
    public static ToolInvocation invocation(String tool, @Nullable String principal, @Nullable String conversationId) {
        return new ToolInvocation(
                UUID.randomUUID().toString(),
                tool,
                JsonObject.builder().put("amount", 1250).put("to", "ACME").build(),
                principal,
                conversationId,
                Clock.systemUTC().instant());
    }

    /**
     * Returns an evaluation where one policy allowed the call.
     *
     * @param policyId the policy
     * @return the evaluation
     */
    public static EvaluationResult allow(String policyId) {
        return EvaluationResult.matched(policyId, Decision.allow());
    }

    private static KeyPair generateKeys() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Ed25519 is not available in this JDK", e);
        }
    }
}
