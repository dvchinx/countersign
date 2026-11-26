package io.github.dvchinx.countersign.core.redaction;

import io.github.dvchinx.countersign.core.model.JsonArray;
import io.github.dvchinx.countersign.core.model.JsonObject;
import io.github.dvchinx.countersign.core.model.JsonString;
import io.github.dvchinx.countersign.core.model.JsonValue;
import io.github.dvchinx.countersign.core.policy.InvalidPolicyException;
import io.github.dvchinx.countersign.core.spi.Redactor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Replaces the value under any of the given keys, at any depth, with a fixed string.
 *
 * <p>For data a pattern cannot recognize but its key names: {@code email}, {@code phone}, {@code ssn}. The whole
 * value under a matching key is replaced, even when it is an object or an array, so nothing under it reaches the
 * model. Keys are compared ignoring case ({@code Email} matches {@code email}), because the same data is often named
 * differently by different tools; redacting too much is safer than too little. Everything else keeps its shape.
 *
 * <p>It only sees keys, so it has no effect on a result that is plain text rather than JSON. Combine it with a
 * {@link PatternRedactor} when the data can also appear in text. Immutable and thread-safe.
 */
public final class FieldRedactor implements Redactor {

    private final Set<String> fields;
    private final JsonString replacement;

    private FieldRedactor(Set<String> fields, String replacement) {
        this.fields = fields;
        this.replacement = JsonString.of(replacement);
    }

    /**
     * Creates a redactor that replaces values with {@link PatternRedactor#DEFAULT_REPLACEMENT}.
     *
     * @param fields the key names, compared ignoring case
     * @return the redactor
     * @throws InvalidPolicyException if no field is given, or one is blank
     */
    public static FieldRedactor of(List<String> fields) {
        return of(fields, PatternRedactor.DEFAULT_REPLACEMENT);
    }

    /**
     * Creates a redactor.
     *
     * @param fields the key names, compared ignoring case
     * @param replacement the string that replaces each value
     * @return the redactor
     * @throws InvalidPolicyException if no field is given, or one is blank
     */
    public static FieldRedactor of(List<String> fields, String replacement) {
        Objects.requireNonNull(fields, "fields");
        Objects.requireNonNull(replacement, "replacement");
        if (fields.isEmpty()) {
            throw new InvalidPolicyException("Field redactor needs at least one field name");
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String field : fields) {
            if (field == null || field.isBlank()) {
                throw new InvalidPolicyException("Field redactor field names must not be blank");
            }
            normalized.add(normalize(field));
        }
        return new FieldRedactor(Set.copyOf(normalized), replacement);
    }

    @Override
    public JsonValue redact(JsonValue value) {
        return switch (value) {
            case JsonObject object -> {
                Map<String, JsonValue> members = new LinkedHashMap<>(object.size());
                object.members()
                        .forEach((name, member) ->
                                members.put(name, fields.contains(normalize(name)) ? replacement : redact(member)));
                yield new JsonObject(members);
            }
            case JsonArray array -> {
                List<JsonValue> elements = new ArrayList<>(array.size());
                for (JsonValue element : array.elements()) {
                    elements.add(redact(element));
                }
                yield new JsonArray(elements);
            }
            default -> value;
        };
    }

    private static String normalize(String field) {
        return field.toLowerCase(Locale.ROOT);
    }

    @Override
    public String toString() {
        return "FieldRedactor" + fields;
    }
}
