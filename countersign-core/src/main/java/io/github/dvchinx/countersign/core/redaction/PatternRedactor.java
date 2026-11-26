package io.github.dvchinx.countersign.core.redaction;

import io.github.dvchinx.countersign.core.model.JsonArray;
import io.github.dvchinx.countersign.core.model.JsonNumber;
import io.github.dvchinx.countersign.core.model.JsonObject;
import io.github.dvchinx.countersign.core.model.JsonString;
import io.github.dvchinx.countersign.core.model.JsonValue;
import io.github.dvchinx.countersign.core.policy.InvalidPolicyException;
import io.github.dvchinx.countersign.core.spi.Redactor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Replaces every match of a regular expression in the string values of a tree, and in its numbers.
 *
 * <p>Numbers are checked too, because the data worth redacting is often sent as a JSON number: a card number
 * {@code 4111111111111111} is a number to the parser. A number is matched on its plain decimal digits
 * ({@code 4111111111111111}, never {@code 4.111111111111111E+15}); if the pattern finds a match, the number becomes a
 * string with the matches replaced. Keys are never changed, and the shape of the tree is kept.
 *
 * <p>The replacement is literal text: {@code $} and {@code \} in it have no special meaning. A pattern that matches
 * the empty string is rejected, because it would insert the replacement between every two characters.
 *
 * <p>The pattern runs on data the model controls. Keep it simple: a pattern with nested repetition can take a long
 * time on a large result, and the tool call waits for it. Immutable and thread-safe.
 */
public final class PatternRedactor implements Redactor {

    /** The replacement used when none is given. */
    public static final String DEFAULT_REPLACEMENT = "[REDACTED]";

    private final Pattern pattern;
    private final String replacement;

    private PatternRedactor(Pattern pattern, String replacement) {
        this.pattern = pattern;
        this.replacement = Matcher.quoteReplacement(replacement);
    }

    /**
     * Creates a redactor that replaces matches with {@link #DEFAULT_REPLACEMENT}.
     *
     * @param regex a {@link Pattern} regular expression
     * @return the redactor
     * @throws InvalidPolicyException if the expression is blank, does not compile, or matches the empty string
     */
    public static PatternRedactor of(String regex) {
        return of(regex, DEFAULT_REPLACEMENT);
    }

    /**
     * Creates a redactor.
     *
     * @param regex a {@link Pattern} regular expression, for example {@code \b\d(?:[ -]?\d){12,18}\b}
     * @param replacement the literal text that replaces each match
     * @return the redactor
     * @throws InvalidPolicyException if the expression is blank, does not compile, or matches the empty string
     */
    public static PatternRedactor of(String regex, String replacement) {
        Objects.requireNonNull(regex, "regex");
        Objects.requireNonNull(replacement, "replacement");
        if (regex.isBlank()) {
            throw new InvalidPolicyException("Redaction pattern must not be blank");
        }
        Pattern pattern;
        try {
            pattern = Pattern.compile(regex);
        } catch (PatternSyntaxException e) {
            throw new InvalidPolicyException(
                    "Redaction pattern is not a valid regular expression: " + e.getDescription(), e);
        }
        if (pattern.matcher("").matches()) {
            throw new InvalidPolicyException("Redaction pattern '" + regex + "' matches the empty string");
        }
        return new PatternRedactor(pattern, replacement);
    }

    @Override
    public JsonValue redact(JsonValue value) {
        return switch (value) {
            case JsonString string -> JsonString.of(replace(string.value()));
            case JsonNumber number -> {
                String digits = number.value().toPlainString();
                yield pattern.matcher(digits).find() ? JsonString.of(replace(digits)) : number;
            }
            case JsonObject object -> {
                Map<String, JsonValue> members = new LinkedHashMap<>(object.size());
                object.members().forEach((name, member) -> members.put(name, redact(member)));
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

    private String replace(String text) {
        return pattern.matcher(text).replaceAll(replacement);
    }

    /**
     * Returns the regular expression.
     *
     * @return the pattern as written
     */
    public String pattern() {
        return pattern.pattern();
    }

    @Override
    public String toString() {
        return "PatternRedactor[" + pattern.pattern() + "]";
    }
}
