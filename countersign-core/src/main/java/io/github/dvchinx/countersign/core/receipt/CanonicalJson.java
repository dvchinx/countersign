package io.github.dvchinx.countersign.core.receipt;

import io.github.dvchinx.countersign.core.model.JsonArray;
import io.github.dvchinx.countersign.core.model.JsonBoolean;
import io.github.dvchinx.countersign.core.model.JsonNull;
import io.github.dvchinx.countersign.core.model.JsonNumber;
import io.github.dvchinx.countersign.core.model.JsonObject;
import io.github.dvchinx.countersign.core.model.JsonString;
import io.github.dvchinx.countersign.core.model.JsonValue;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;

/**
 * Writes a {@link JsonValue} in a canonical form, so that equal values always produce the same bytes and therefore
 * the same hash.
 *
 * <p>Rules (ADR 0003), close to RFC 8785 except for numbers:
 *
 * <ul>
 *   <li>No whitespace between tokens.
 *   <li>Object members are sorted by name, comparing UTF-16 code units ({@link String#compareTo}).
 *   <li>Strings escape {@code "}, {@code \} and control characters below U+0020: {@code \b \t \n \f \r} use their
 *       short form, the rest {@code \}{@code u00xx} in lowercase hex. Lone surrogates are escaped the same way, so two
 *       different strings never encode to the same UTF-8 bytes. Everything else, {@code /} and non-ASCII included, is
 *       written as is.
 *   <li>Numbers are written as {@link BigDecimal#stripTrailingZeros()} followed by {@link BigDecimal#toString()},
 *       and zero as {@code 0}. So {@code 1000} and {@code 1000.00} both become {@code 1E+3}. RFC 8785 formats numbers
 *       as IEEE doubles instead, which would lose the exact value of large or precise amounts.
 *   <li>The output is encoded as UTF-8.
 * </ul>
 */
public final class CanonicalJson {

    private static final HexFormat HEX = HexFormat.of();

    private CanonicalJson() {}

    /**
     * Writes a value in canonical form.
     *
     * @param value the value
     * @return the canonical JSON text
     */
    public static String write(JsonValue value) {
        StringBuilder out = new StringBuilder();
        append(out, value);
        return out.toString();
    }

    /**
     * Returns the SHA-256 of a value's canonical form, as lowercase hex. This is how receipts hash arguments.
     *
     * @param value the value
     * @return 64 lowercase hex characters
     */
    public static String sha256Hex(JsonValue value) {
        return HEX.formatHex(sha256(write(value).getBytes(StandardCharsets.UTF_8)));
    }

    static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            // Every Java SE implementation must provide SHA-256.
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static void append(StringBuilder out, JsonValue value) {
        switch (value) {
            case JsonNull ignored -> out.append("null");
            case JsonBoolean bool -> out.append(bool.value());
            case JsonNumber number -> out.append(number(number.value()));
            case JsonString string -> appendString(out, string.value());
            case JsonArray array -> {
                out.append('[');
                for (int i = 0; i < array.size(); i++) {
                    if (i > 0) {
                        out.append(',');
                    }
                    append(out, array.get(i));
                }
                out.append(']');
            }
            case JsonObject object -> {
                out.append('{');
                Iterator<Map.Entry<String, JsonValue>> members =
                        new TreeMap<>(object.members()).entrySet().iterator();
                while (members.hasNext()) {
                    Map.Entry<String, JsonValue> member = members.next();
                    appendString(out, member.getKey());
                    out.append(':');
                    append(out, member.getValue());
                    if (members.hasNext()) {
                        out.append(',');
                    }
                }
                out.append('}');
            }
        }
    }

    private static String number(BigDecimal value) {
        return value.signum() == 0 ? "0" : value.stripTrailingZeros().toString();
    }

    private static void appendString(StringBuilder out, String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\t' -> out.append("\\t");
                case '\n' -> out.append("\\n");
                case '\f' -> out.append("\\f");
                case '\r' -> out.append("\\r");
                default -> {
                    if (c < 0x20) {
                        appendUnicodeEscape(out, c);
                    } else if (Character.isHighSurrogate(c)
                            && i + 1 < value.length()
                            && Character.isLowSurrogate(value.charAt(i + 1))) {
                        out.append(c).append(value.charAt(++i));
                    } else if (Character.isSurrogate(c)) {
                        appendUnicodeEscape(out, c);
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    private static void appendUnicodeEscape(StringBuilder out, char c) {
        out.append("\\u").append(HEX.toHexDigits(c));
    }
}
