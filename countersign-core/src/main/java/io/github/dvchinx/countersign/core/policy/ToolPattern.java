package io.github.dvchinx.countersign.core.policy;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A glob that selects tools by name, such as {@code db.*} or {@code payments.transfer}.
 *
 * <p>The only wildcard is {@code *}, which matches any run of characters, dots included: {@code db.*} matches
 * {@code db.query} and {@code db.admin.drop}. Every other character, {@code ?} included, matches itself, and
 * matching is case-sensitive. Keeping the syntax this small means a pattern means the same thing to everyone who
 * reads it.
 */
public final class ToolPattern {

    private final String glob;
    private final Pattern regex;

    private ToolPattern(String glob) {
        this.glob = glob;
        this.regex = toRegex(glob);
    }

    /**
     * Parses a glob.
     *
     * @param glob the glob, for example {@code db.*}
     * @return the pattern
     * @throws InvalidPolicyException if the glob is blank or contains whitespace
     */
    public static ToolPattern of(String glob) {
        Objects.requireNonNull(glob, "glob");
        if (glob.isBlank()) {
            throw new InvalidPolicyException("Tool pattern must not be blank");
        }
        if (glob.chars().anyMatch(Character::isWhitespace)) {
            throw new InvalidPolicyException("Tool pattern must not contain whitespace: '" + glob + "'");
        }
        return new ToolPattern(glob);
    }

    /**
     * Tells whether a tool name matches this pattern.
     *
     * @param toolName the tool's name
     * @return {@code true} if the whole name matches
     */
    public boolean matches(String toolName) {
        return regex.matcher(toolName).matches();
    }

    /**
     * Returns the glob this pattern was parsed from.
     *
     * @return the glob
     */
    public String glob() {
        return glob;
    }

    private static Pattern toRegex(String glob) {
        StringBuilder regex = new StringBuilder();
        int start = 0;
        for (int star = glob.indexOf('*'); star >= 0; star = glob.indexOf('*', start)) {
            if (star > start) {
                regex.append(Pattern.quote(glob.substring(start, star)));
            }
            regex.append(".*");
            start = star + 1;
        }
        if (start < glob.length()) {
            regex.append(Pattern.quote(glob.substring(start)));
        }
        return Pattern.compile(regex.toString(), Pattern.DOTALL);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ToolPattern pattern && glob.equals(pattern.glob);
    }

    @Override
    public int hashCode() {
        return glob.hashCode();
    }

    @Override
    public String toString() {
        return glob;
    }
}
