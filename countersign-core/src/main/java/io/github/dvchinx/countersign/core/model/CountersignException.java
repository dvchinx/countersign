package io.github.dvchinx.countersign.core.model;

import java.io.Serial;

/**
 * Base class for every error Countersign raises on purpose.
 *
 * <p>A single base type lets adapters tell a Countersign error apart from a bug in a tool, and turn it into a
 * structured refusal instead of a stack trace sent to the model. Messages must never contain argument values or
 * secrets.
 */
public abstract class CountersignException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Creates an exception with a message.
     *
     * @param message what went wrong, without argument values or secrets
     */
    protected CountersignException(String message) {
        super(message);
    }

    /**
     * Creates an exception with a message and a cause.
     *
     * @param message what went wrong, without argument values or secrets
     * @param cause the underlying error
     */
    protected CountersignException(String message, Throwable cause) {
        super(message, cause);
    }
}
