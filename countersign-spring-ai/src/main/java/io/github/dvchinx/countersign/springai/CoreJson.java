package io.github.dvchinx.countersign.springai;

import io.github.dvchinx.countersign.core.model.JsonArray;
import io.github.dvchinx.countersign.core.model.JsonBoolean;
import io.github.dvchinx.countersign.core.model.JsonNull;
import io.github.dvchinx.countersign.core.model.JsonNumber;
import io.github.dvchinx.countersign.core.model.JsonObject;
import io.github.dvchinx.countersign.core.model.JsonString;
import io.github.dvchinx.countersign.core.model.JsonValue;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.core.StreamWriteFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** Converts between Jackson trees and the core's JSON tree (ADR 0002), and writes core trees as JSON text. */
final class CoreJson {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    // Plain numbers, so a redacted result shows 5000, not 5E+3, where the tool wrote 5000.
    private static final JsonMapper WRITER = JsonMapper.builder()
            .enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN)
            .build();

    private CoreJson() {}

    /**
     * Converts a Jackson node.
     *
     * @throws UnsupportedNodeException for node types plain JSON text cannot produce (binary, POJO)
     */
    static JsonValue fromJackson(JsonNode node) {
        if (node.isObject()) {
            Map<String, JsonValue> members = new LinkedHashMap<>();
            for (Map.Entry<String, JsonNode> member : node.properties()) {
                members.put(member.getKey(), fromJackson(member.getValue()));
            }
            return new JsonObject(members);
        }
        if (node.isArray()) {
            List<JsonValue> elements = new ArrayList<>(node.size());
            for (JsonNode element : node.values()) {
                elements.add(fromJackson(element));
            }
            return new JsonArray(elements);
        }
        if (node.isString()) {
            return JsonString.of(node.stringValue());
        }
        if (node.isNumber()) {
            return JsonNumber.of(node.decimalValue());
        }
        if (node.isBoolean()) {
            return JsonBoolean.of(node.booleanValue());
        }
        if (node.isNull()) {
            return JsonNull.INSTANCE;
        }
        throw new UnsupportedNodeException();
    }

    /** Writes a core tree as compact JSON, keeping member order and the exact value of numbers. */
    static String write(JsonValue value) {
        return WRITER.writeValueAsString(toJackson(value));
    }

    private static JsonNode toJackson(JsonValue value) {
        return switch (value) {
            case JsonObject object -> {
                ObjectNode node = NODES.objectNode();
                object.members().forEach((name, member) -> node.set(name, toJackson(member)));
                yield node;
            }
            case JsonArray array -> {
                ArrayNode node = NODES.arrayNode(array.size());
                array.elements().forEach(element -> node.add(toJackson(element)));
                yield node;
            }
            case JsonString string -> NODES.stringNode(string.value());
            case JsonNumber number -> number(number.value());
            case JsonBoolean bool -> NODES.booleanNode(bool.value());
            case JsonNull ignored -> NODES.nullNode();
        };
    }

    private static JsonNode number(BigDecimal value) {
        // Whole numbers as integers, so 5000 is written as 5000 and not 5000.0 or 5E+3.
        return value.scale() <= 0 ? NODES.numberNode(value.toBigIntegerExact()) : NODES.numberNode(value);
    }

    /** A node type plain JSON text cannot produce (binary, POJO); treated as invalid input. */
    static final class UnsupportedNodeException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        UnsupportedNodeException() {
            super(null, null, false, false);
        }
    }
}
