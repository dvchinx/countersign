package io.github.dvchinx.countersign.core.model;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * A JSON number, held as a {@link BigDecimal}.
 *
 * <p>{@code BigDecimal} keeps the exact value the model sent. A {@code double} would turn {@code 1000.1} into
 * {@code 1000.0999999999999}, which matters when a policy compares money amounts against a limit.
 *
 * <p>Two numbers are equal when they have the same numeric value, whatever their scale: {@code 1000} equals
 * {@code 1000.00}. This differs from {@link BigDecimal#equals(Object)} on purpose, because the model may format the
 * same amount either way.
 *
 * @param value the numeric value
 */
public record JsonNumber(BigDecimal value) implements JsonValue {

    /**
     * Creates a JSON number.
     *
     * @param value the numeric value, never {@code null} (use {@link JsonNull#INSTANCE})
     */
    public JsonNumber {
        Objects.requireNonNull(value, "value");
    }

    /**
     * Creates a JSON number from a {@code long}.
     *
     * @param value the numeric value
     * @return the JSON number
     */
    public static JsonNumber of(long value) {
        return new JsonNumber(BigDecimal.valueOf(value));
    }

    /**
     * Creates a JSON number from a {@code double}, using its shortest decimal representation.
     *
     * @param value the numeric value
     * @return the JSON number
     * @throws IllegalArgumentException if {@code value} is NaN or infinite, which JSON cannot represent
     */
    public static JsonNumber of(double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("JSON numbers must be finite");
        }
        return new JsonNumber(BigDecimal.valueOf(value));
    }

    /**
     * Creates a JSON number.
     *
     * @param value the numeric value
     * @return the JSON number
     */
    public static JsonNumber of(BigDecimal value) {
        return new JsonNumber(value);
    }

    @Override
    public BigDecimal toJava() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof JsonNumber number && value.compareTo(number.value) == 0;
    }

    @Override
    public int hashCode() {
        // Consistent with equals: 1000 and 1000.00 strip to the same value and scale.
        return value.signum() == 0 ? 0 : value.stripTrailingZeros().hashCode();
    }

    @Override
    public String toString() {
        return "JsonNumber[redacted]";
    }
}
