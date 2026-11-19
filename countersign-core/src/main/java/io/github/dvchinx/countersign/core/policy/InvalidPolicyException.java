package io.github.dvchinx.countersign.core.policy;

import io.github.dvchinx.countersign.core.model.CountersignException;
import java.io.Serial;

/**
 * A policy, or a set of policies, is not valid: a blank id, a duplicate id, a malformed tool pattern or condition.
 *
 * <p>Raised when policies are built, which in a Spring Boot app means at startup. Failing there is the point: a
 * typo in a policy must stop the app, not silently leave a tool unguarded.
 */
public class InvalidPolicyException extends CountersignException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message what is wrong with the policy
     */
    public InvalidPolicyException(String message) {
        super(message);
    }

    /**
     * Creates the exception with a cause, for example a parse error in a condition.
     *
     * @param message what is wrong with the policy
     * @param cause the underlying error
     */
    public InvalidPolicyException(String message, Throwable cause) {
        super(message, cause);
    }
}
