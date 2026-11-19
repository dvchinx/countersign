package io.github.dvchinx.countersign.core.model;

import java.util.Objects;

/**
 * A JSON string.
 *
 * <p>{@link #toString()} does not return the value, so that logging an invocation by accident does not leak an
 * argument (design principle 3: never log raw arguments).
 *
 * @param value the string value
 */
public record JsonString(String value) implements JsonValue {

    /**
     * Creates a JSON string.
     *
     * @param value the string value, never {@code null} (use {@link JsonNull#INSTANCE})
     */
    public JsonString {
        Objects.requireNonNull(value, "value");
    }

    /**
     * Creates a JSON string.
     *
     * @param value the string value
     * @return the JSON string
     */
    public static JsonString of(String value) {
        return new JsonString(value);
    }

    @Override
    public String toJava() {
        return value;
    }

    @Override
    public String toString() {
        return "JsonString[length=" + value.length() + "]";
    }
}
