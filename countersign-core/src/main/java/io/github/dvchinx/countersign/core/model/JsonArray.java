package io.github.dvchinx.countersign.core.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A JSON array. Immutable: the list is copied on construction.
 *
 * @param elements the elements, in order; use {@link JsonNull#INSTANCE} for JSON {@code null}
 */
public record JsonArray(List<JsonValue> elements) implements JsonValue {

    /**
     * Creates a JSON array.
     *
     * @param elements the elements, in order; neither the list nor any element may be {@code null}
     */
    public JsonArray {
        elements = List.copyOf(elements);
    }

    /**
     * Creates a JSON array.
     *
     * @param elements the elements, in order
     * @return the JSON array
     */
    public static JsonArray of(JsonValue... elements) {
        return new JsonArray(List.of(elements));
    }

    /**
     * Returns the number of elements.
     *
     * @return the number of elements
     */
    public int size() {
        return elements.size();
    }

    /**
     * Returns the element at an index.
     *
     * @param index the zero-based index
     * @return the element
     * @throws IndexOutOfBoundsException if the index is out of range
     */
    public JsonValue get(int index) {
        return elements.get(index);
    }

    @Override
    public List<Object> toJava() {
        // List.copyOf rejects nulls, and JSON null elements map to Java null.
        List<Object> list = new ArrayList<>(elements.size());
        for (JsonValue element : elements) {
            list.add(element.toJava());
        }
        return Collections.unmodifiableList(list);
    }

    @Override
    public String toString() {
        return "JsonArray[size=" + elements.size() + "]";
    }
}
