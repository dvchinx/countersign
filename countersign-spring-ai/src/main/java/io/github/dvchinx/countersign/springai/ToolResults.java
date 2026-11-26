package io.github.dvchinx.countersign.springai;

import io.github.dvchinx.countersign.core.guard.ResultRedaction;
import io.github.dvchinx.countersign.core.guard.ResultWithheldException;
import io.github.dvchinx.countersign.core.model.JsonString;
import io.github.dvchinx.countersign.core.model.JsonValue;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Applies a {@link ResultRedaction} to the text a tool returned.
 *
 * <p>Spring AI tools return text, usually JSON written by the tool's result converter (a {@code String} return value
 * becomes a JSON string). A result that is one valid JSON value is redacted as a tree, so field redactors see its keys;
 * any other text is redacted as one string value.
 *
 * <p>Once a redaction applies, the model gets this class's serialization of the redacted tree, never the tool's
 * original text, even when no redactor changed anything. Returning the original text "when nothing changed" would be
 * unsafe: a lenient parser keeps only the last of two duplicate keys, so the original text could still carry a value
 * the redactors never saw.
 */
final class ToolResults {

    private static final JsonMapper READER = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private ToolResults() {}

    /**
     * Redacts a tool result.
     *
     * @param result the text the tool returned
     * @param redaction what to redact
     * @return the text to give the model
     * @throws ResultWithheldException if a redactor fails; the result must be withheld
     */
    static String redact(String result, ResultRedaction redaction) {
        JsonValue tree = parse(result);
        if (tree == null) {
            JsonValue redacted = redaction.apply(JsonString.of(result));
            // Text stays text, unless a custom redactor turned it into something else.
            return redacted instanceof JsonString text ? text.value() : CoreJson.write(redacted);
        }
        return CoreJson.write(redaction.apply(tree));
    }

    /** The result as one JSON value, or {@code null} if it is not exactly one. */
    private static @Nullable JsonValue parse(String result) {
        if (result.isBlank()) {
            return null;
        }
        try {
            JsonNode root = READER.readTree(result);
            return root == null || root.isMissingNode() ? null : CoreJson.fromJackson(root);
        } catch (JacksonException | CoreJson.UnsupportedNodeException e) {
            return null;
        }
    }
}
