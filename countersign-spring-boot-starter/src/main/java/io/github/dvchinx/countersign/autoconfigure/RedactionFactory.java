package io.github.dvchinx.countersign.autoconfigure;

import io.github.dvchinx.countersign.autoconfigure.CountersignProperties.RedactionProperties;
import io.github.dvchinx.countersign.autoconfigure.CountersignProperties.RedactorProperties;
import io.github.dvchinx.countersign.core.policy.Condition;
import io.github.dvchinx.countersign.core.policy.InvalidPolicyException;
import io.github.dvchinx.countersign.core.policy.ToolPattern;
import io.github.dvchinx.countersign.core.redaction.FieldRedactor;
import io.github.dvchinx.countersign.core.redaction.PatternRedactor;
import io.github.dvchinx.countersign.core.redaction.RedactionRule;
import io.github.dvchinx.countersign.core.spi.Redactor;
import io.github.dvchinx.countersign.springai.spel.SpelCondition;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns {@code countersign.redactors} and {@code countersign.redactions} into {@link RedactionRule}s, failing with the
 * property path on any error, so a typo stops the application instead of leaving a result unredacted.
 */
final class RedactionFactory {

    private RedactionFactory() {}

    /**
     * Builds the configured rules.
     *
     * @param properties the configuration
     * @param redactorBeans the application's {@link Redactor} beans, by bean name
     * @return the rules, in the order listed
     * @throws InvalidPolicyException naming the property, if a redactor or rule is not valid
     */
    static List<RedactionRule> fromProperties(CountersignProperties properties, Map<String, Redactor> redactorBeans) {
        Map<String, Redactor> redactors = redactors(properties.getRedactors(), redactorBeans);
        List<RedactionProperties> configured = properties.getRedactions();
        List<RedactionRule> rules = new ArrayList<>(configured.size());
        for (int i = 0; i < configured.size(); i++) {
            try {
                rules.add(toRule(configured.get(i), redactors));
            } catch (InvalidPolicyException e) {
                throw new InvalidPolicyException("countersign.redactions[" + i + "]: " + e.getMessage(), e);
            }
        }
        return rules;
    }

    private static Map<String, Redactor> redactors(
            Map<String, RedactorProperties> configured, Map<String, Redactor> beans) {
        Map<String, Redactor> redactors = new LinkedHashMap<>(beans);
        configured.forEach((name, properties) -> {
            String path = "countersign.redactors." + name;
            if (beans.containsKey(name)) {
                throw new InvalidPolicyException(path + ": a Redactor bean has the same name; rename one of them");
            }
            try {
                redactors.put(name, toRedactor(properties));
            } catch (InvalidPolicyException e) {
                throw new InvalidPolicyException(path + ": " + e.getMessage(), e);
            }
        });
        return redactors;
    }

    private static Redactor toRedactor(RedactorProperties properties) {
        String pattern = properties.getPattern();
        boolean hasPattern = pattern != null && !pattern.isBlank();
        boolean hasFields = !properties.getFields().isEmpty();
        if (hasPattern == hasFields) {
            throw new InvalidPolicyException("set either 'pattern' or 'fields', not both or neither");
        }
        return hasPattern
                ? PatternRedactor.of(pattern, properties.getReplacement())
                : FieldRedactor.of(properties.getFields(), properties.getReplacement());
    }

    private static RedactionRule toRule(RedactionProperties properties, Map<String, Redactor> redactors) {
        String id = properties.getId();
        if (id == null || id.isBlank()) {
            throw new InvalidPolicyException("'id' is required");
        }
        if (properties.getTarget().isEmpty()) {
            throw new InvalidPolicyException("redaction rule '" + id + "' needs a 'target' (args, result or both)");
        }
        if (properties.getRedactors().isEmpty()) {
            throw new InvalidPolicyException("redaction rule '" + id + "' needs at least one entry in 'redactors'");
        }
        List<Redactor> chosen = new ArrayList<>(properties.getRedactors().size());
        for (String name : properties.getRedactors()) {
            Redactor redactor = redactors.get(name);
            if (redactor == null) {
                throw new InvalidPolicyException("redaction rule '" + id + "' uses unknown redactor '" + name
                        + "'; define it under countersign.redactors or as a Redactor bean");
            }
            chosen.add(redactor);
        }
        List<ToolPattern> tools =
                properties.getTools().stream().map(ToolPattern::of).toList();
        String when = properties.getWhen();
        Condition condition = when == null || when.isBlank() ? Condition.always() : SpelCondition.parse(when);
        return new RedactionRule(id, tools, condition, properties.getTarget(), chosen);
    }
}
