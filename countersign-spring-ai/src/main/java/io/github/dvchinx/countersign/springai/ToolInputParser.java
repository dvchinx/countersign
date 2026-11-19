package io.github.dvchinx.countersign.springai;

import io.github.dvchinx.countersign.core.model.JsonArray;
import io.github.dvchinx.countersign.core.model.JsonBoolean;
import io.github.dvchinx.countersign.core.model.JsonNull;
import io.github.dvchinx.countersign.core.model.JsonNumber;
import io.github.dvchinx.countersign.core.model.JsonObject;
import io.github.dvchinx.countersign.core.model.JsonString;
import io.github.dvchinx.countersign.core.model.JsonValue;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Parses the raw tool input sent by the model into the core's JSON tree.
 *
 * <p>Strict on purpose. Policies judge the parsed arguments, but the tool parses the raw text again on its own; if
 * the two parsers could read the same text differently, a model could show the policy one value and the tool
 * another. So input that a lenient parser might accept is rejected:
 *
 * <ul>
 *   <li>duplicate member names ({@code {"amount": 1, "amount": 5000}}), which parsers resolve differently;
 *   <li>trailing content after the object;
 *   <li>anything that is not a JSON object.
 * </ul>
 *
 * <p>Decimals are read as {@code BigDecimal}, so amounts keep their exact value.
 */
final class ToolInputParser {

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private ToolInputParser() {}

    /**
     * Parses tool input.
     *
     * @param input the raw input; blank means no arguments
     * @return the arguments, or {@code null} if the input is not a single, well-formed JSON object
     */
    static @Nullable JsonObject parse(@Nullable String input) {
        if (input == null || input.isBlank()) {
            return JsonObject.empty();
        }
        try {
            JsonNode root = MAPPER.readTree(input);
            return root != null && root.isObject() ? (JsonObject) convert(root) : null;
        } catch (JacksonException | UnsupportedNodeException e) {
            return null;
        }
    }

    private static JsonValue convert(JsonNode node) {
        if (node.isObject()) {
            Map<String, JsonValue> members = new LinkedHashMap<>();
            for (Map.Entry<String, JsonNode> member : node.properties()) {
                members.put(member.getKey(), convert(member.getValue()));
            }
            return new JsonObject(members);
        }
        if (node.isArray()) {
            List<JsonValue> elements = new ArrayList<>(node.size());
            for (JsonNode element : node.values()) {
                elements.add(convert(element));
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

    /** A node type plain JSON text cannot produce (binary, POJO); treated as invalid input. */
    private static final class UnsupportedNodeException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        UnsupportedNodeException() {
            super(null, null, false, false);
        }
    }
}
