package io.github.dvchinx.countersign.springai;

import io.github.dvchinx.countersign.core.model.JsonObject;
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
            return root != null && root.isObject() ? (JsonObject) CoreJson.fromJackson(root) : null;
        } catch (JacksonException | CoreJson.UnsupportedNodeException e) {
            return null;
        }
    }
}
