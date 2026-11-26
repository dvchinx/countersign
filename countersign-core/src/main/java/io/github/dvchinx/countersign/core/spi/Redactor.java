package io.github.dvchinx.countersign.core.spi;

import io.github.dvchinx.countersign.core.model.JsonArray;
import io.github.dvchinx.countersign.core.model.JsonObject;
import io.github.dvchinx.countersign.core.model.JsonString;
import io.github.dvchinx.countersign.core.model.JsonValue;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * Removes or masks data in a tool's arguments or result, before it goes where it should not (ADR 0015).
 *
 * <p>A port, so that any detector can plug in: the two built-ins in {@code core.redaction} (by pattern and by field
 * name), a locale-specific one for national ids or IBANs, or a lambda. It works on the core JSON tree, not on text: a
 * regular expression run over JSON text can cut through quotes and escapes and produce invalid JSON, and it cannot see
 * keys. Keep the shape of the tree where you can, so the model still gets the structure it expects.
 *
 * <p>Implementations must be:
 *
 * <ul>
 *   <li><b>deterministic</b>: the same input always gives the same output (design principle 2), so an investigator who
 *       holds the original data can recompute exactly what was redacted;
 *   <li><b>silent</b>: a redactor sees every value it applies to, so it must never log what it reads;
 *   <li><b>fast and free of I/O</b>: the tool call waits for it, and it runs on data the model controls;
 *   <li><b>thread-safe</b>: it is called from every tool call at once.
 * </ul>
 *
 * <p>A redactor may throw. Countersign then fails closed: a result that could not be redacted is withheld from the
 * model, and the failure is logged and counted.
 */
@FunctionalInterface
public interface Redactor {

    /**
     * Returns the value with the data this redactor removes replaced.
     *
     * @param value the arguments or result, as a JSON tree; never {@code null}
     * @return the redacted value; never {@code null} (use {@link io.github.dvchinx.countersign.core.model.JsonNull})
     */
    JsonValue redact(JsonValue value);

    /**
     * Builds a redactor that applies a function to every string value in the tree, at any depth. Keys, numbers,
     * booleans and nulls are left as they are.
     *
     * <p>The simplest way to write a custom rule:
     *
     * <pre>{@code
     * Redactor emails = Redactor.strings(text -> EMAIL.matcher(text).replaceAll("[EMAIL]"));
     * }</pre>
     *
     * @param redaction the function applied to each string value; must not return {@code null}
     * @return the redactor
     */
    static Redactor strings(UnaryOperator<String> redaction) {
        Objects.requireNonNull(redaction, "redaction");
        return value -> mapStrings(value, redaction);
    }

    private static JsonValue mapStrings(JsonValue value, UnaryOperator<String> redaction) {
        return switch (value) {
            case JsonString string ->
                JsonString.of(Objects.requireNonNull(
                        redaction.apply(string.value()), "the redaction function returned null"));
            case JsonObject object -> {
                Map<String, JsonValue> members = new LinkedHashMap<>(object.size());
                object.members().forEach((name, member) -> members.put(name, mapStrings(member, redaction)));
                yield new JsonObject(members);
            }
            case JsonArray array -> {
                List<JsonValue> elements = new ArrayList<>(array.size());
                for (JsonValue element : array.elements()) {
                    elements.add(mapStrings(element, redaction));
                }
                yield new JsonArray(elements);
            }
            default -> value;
        };
    }
}
