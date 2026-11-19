package io.github.dvchinx.countersign.core.receipt;

import io.github.dvchinx.countersign.core.model.CountersignException;
import java.io.Serial;

/**
 * A receipt could not be created, for example because signing failed or a key is not valid.
 *
 * <p>Adapters treat it like any other failure on the decision path: the tool does not run.
 */
public class ReceiptException extends CountersignException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message what went wrong, without key material
     */
    public ReceiptException(String message) {
        super(message);
    }

    /**
     * Creates the exception with a cause.
     *
     * @param message what went wrong, without key material
     * @param cause the underlying error
     */
    public ReceiptException(String message, Throwable cause) {
        super(message, cause);
    }
}
