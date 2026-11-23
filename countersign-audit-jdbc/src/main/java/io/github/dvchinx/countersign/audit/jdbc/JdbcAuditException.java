package io.github.dvchinx.countersign.audit.jdbc;

import io.github.dvchinx.countersign.core.model.CountersignException;
import java.io.Serial;

/**
 * A stored receipt could not be read back, or the schema script could not be loaded.
 *
 * <p>Database errors themselves surface as Spring's {@code DataAccessException}; this one is for rows and resources
 * that are there but are not what Countersign wrote.
 */
public class JdbcAuditException extends CountersignException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Creates an exception.
     *
     * @param message what went wrong
     */
    public JdbcAuditException(String message) {
        super(message);
    }

    /**
     * Creates an exception with a cause.
     *
     * @param message what went wrong
     * @param cause the underlying error
     */
    public JdbcAuditException(String message, Throwable cause) {
        super(message, cause);
    }
}
