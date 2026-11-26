package io.github.dvchinx.countersign.autoconfigure;

import io.github.dvchinx.countersign.core.model.CountersignException;
import io.github.dvchinx.countersign.core.policy.InvalidPolicyException;
import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/**
 * Turns a Countersign configuration error at startup into a short report that says what to fix, instead of a long
 * bean-creation stack trace.
 */
class CountersignFailureAnalyzer extends AbstractFailureAnalyzer<CountersignException> {

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, CountersignException cause) {
        String message = cause.getMessage();
        if (cause instanceof InvalidPolicyException && message != null && message.startsWith("countersign.redact")) {
            return new FailureAnalysis(
                    "Countersign could not load its redaction rules: " + message,
                    "Fix the property named above. Each entry under countersign.redactors needs either 'pattern' (a"
                            + " valid regular expression that cannot match empty text) or 'fields'. Each rule under"
                            + " countersign.redactions needs a unique id, at least one tool pattern, a 'target' (args, result or both)"
                            + " and the names of known redactors; 'when' must be a valid SpEL expression.",
                    cause);
        }
        if (cause instanceof InvalidPolicyException) {
            return new FailureAnalysis(
                    "Countersign could not load its policies: " + cause.getMessage(),
                    "Fix the policy under countersign.policies. Each policy needs an id, at least one tool pattern and"
                            + " a decision (allow, deny or require-approval); 'when' must be a valid SpEL expression.",
                    cause);
        }
        return new FailureAnalysis(
                "Countersign could not start: " + cause.getMessage(),
                "Check the countersign.receipts.* keys: signing-key must be an Ed25519 private key (PKCS#8, Base64)"
                        + " and args-hmac-key at least 32 random bytes (Base64).",
                cause);
    }
}
