package io.github.dvchinx.countersign.core.model;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * A JSON object. Immutable, and keeps the order of its members as they were added.
 *
 * <p>Member order is kept so that what users see in logs and debuggers matches what the model sent. It does not
 * affect equality, and receipts hash a canonical form that sorts the keys.
 *
 * @param members the members, by name; use {@link JsonNull#INSTANCE} for JSON {@code null}
 */
public record JsonObject(Map<String, JsonValue> members) implements JsonValue {

    private static final JsonObject EMPTY = new JsonObject(Map.of());

    /**
     * Creates a JSON object.
     *
     * @param members the members, by name; neither the map nor any key or value may be {@code null}
     */
    public JsonObject {
        Map<String, JsonValue> copy = new LinkedHashMap<>(members.size());
        members.forEach((name, value) ->
                copy.put(Objects.requireNonNull(name, "member name"), Objects.requireNonNull(value, "member value")));
        members = Collections.unmodifiableMap(copy);
    }

    /**
     * Returns the empty JSON object, used for tools that take no arguments.
     *
     * @return the empty JSON object
     */
    public static JsonObject empty() {
        return EMPTY;
    }

    /**
     * Starts building a JSON object. Mostly useful in tests and in code that builds invocations by hand.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns a member by name.
     *
     * @param name the member name
     * @return the member, or {@code null} if this object has no member with that name (a JSON {@code null} member
     *     returns {@link JsonNull#INSTANCE})
     */
    public @Nullable JsonValue get(String name) {
        return members.get(name);
    }

    /**
     * Returns the number of members.
     *
     * @return the number of members
     */
    public int size() {
        return members.size();
    }

    @Override
    public Map<String, @Nullable Object> toJava() {
        Map<String, @Nullable Object> map = new LinkedHashMap<>(members.size());
        members.forEach((name, value) -> map.put(name, value.toJava()));
        return Collections.unmodifiableMap(map);
    }

    @Override
    public String toString() {
        // Names only: values may be sensitive (design principle 3).
        return "JsonObject" + members.keySet();
    }

    /** Builds a {@link JsonObject} member by member. Not thread-safe. */
    public static final class Builder {

        private final Map<String, JsonValue> members = new LinkedHashMap<>();

        private Builder() {}

        /**
         * Adds a member, replacing any member with the same name.
         *
         * @param name the member name
         * @param value the member value
         * @return this builder
         */
        public Builder put(String name, JsonValue value) {
            members.put(Objects.requireNonNull(name, "name"), Objects.requireNonNull(value, "value"));
            return this;
        }

        /**
         * Adds a string member.
         *
         * @param name the member name
         * @param value the member value
         * @return this builder
         */
        public Builder put(String name, String value) {
            return put(name, JsonString.of(value));
        }

        /**
         * Adds a number member.
         *
         * @param name the member name
         * @param value the member value
         * @return this builder
         */
        public Builder put(String name, long value) {
            return put(name, JsonNumber.of(value));
        }

        /**
         * Adds a number member.
         *
         * @param name the member name
         * @param value the member value
         * @return this builder
         */
        public Builder put(String name, BigDecimal value) {
            return put(name, JsonNumber.of(value));
        }

        /**
         * Adds a boolean member.
         *
         * @param name the member name
         * @param value the member value
         * @return this builder
         */
        public Builder put(String name, boolean value) {
            return put(name, JsonBoolean.of(value));
        }

        /**
         * Adds a JSON {@code null} member.
         *
         * @param name the member name
         * @return this builder
         */
        public Builder putNull(String name) {
            return put(name, JsonNull.INSTANCE);
        }

        /**
         * Builds the object.
         *
         * @return a new, immutable JSON object
         */
        public JsonObject build() {
            return new JsonObject(members);
        }
    }
}
