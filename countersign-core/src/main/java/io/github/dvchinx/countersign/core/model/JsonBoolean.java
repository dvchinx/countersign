package io.github.dvchinx.countersign.core.model;

/**
 * A JSON {@code true} or {@code false}.
 *
 * @param value the boolean value
 */
public record JsonBoolean(boolean value) implements JsonValue {

    /** The JSON {@code true} literal. */
    public static final JsonBoolean TRUE = new JsonBoolean(true);

    /** The JSON {@code false} literal. */
    public static final JsonBoolean FALSE = new JsonBoolean(false);

    /**
     * Returns the shared instance for a boolean, so callers do not allocate one per argument.
     *
     * @param value the boolean value
     * @return {@link #TRUE} or {@link #FALSE}
     */
    public static JsonBoolean of(boolean value) {
        return value ? TRUE : FALSE;
    }

    @Override
    public Boolean toJava() {
        return value;
    }

    @Override
    public String toString() {
        return Boolean.toString(value);
    }
}
