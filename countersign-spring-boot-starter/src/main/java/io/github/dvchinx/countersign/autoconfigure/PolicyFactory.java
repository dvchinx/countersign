package io.github.dvchinx.countersign.autoconfigure;

import io.github.dvchinx.countersign.autoconfigure.CountersignProperties.PolicyProperties;
import io.github.dvchinx.countersign.core.model.Decision;
import io.github.dvchinx.countersign.core.policy.Condition;
import io.github.dvchinx.countersign.core.policy.InvalidPolicyException;
import io.github.dvchinx.countersign.core.policy.Policy;
import io.github.dvchinx.countersign.core.policy.ToolPattern;
import io.github.dvchinx.countersign.springai.spel.SpelCondition;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Turns {@code countersign.policies} into {@link Policy} objects, failing with the property path on any error. */
final class PolicyFactory {

    private PolicyFactory() {}

    static List<Policy> fromProperties(List<PolicyProperties> configured) {
        List<Policy> policies = new ArrayList<>(configured.size());
        for (int i = 0; i < configured.size(); i++) {
            PolicyProperties properties = configured.get(i);
            String path = "countersign.policies[" + i + "]";
            try {
                policies.add(toPolicy(properties));
            } catch (InvalidPolicyException e) {
                throw new InvalidPolicyException(path + ": " + e.getMessage(), e);
            }
        }
        return policies;
    }

    static Decision defaultDecision(CountersignProperties properties) {
        return switch (properties.getDefaultDecision()) {
            case ALLOW -> Decision.allow();
            case DENY -> Decision.deny(properties.getDefaultReason());
            case REQUIRE_APPROVAL -> Decision.requireApproval(properties.getDefaultReason());
        };
    }

    private static Policy toPolicy(PolicyProperties properties) {
        String id = properties.getId();
        if (id == null || id.isBlank()) {
            throw new InvalidPolicyException("'id' is required");
        }
        Decision.Type type = properties.getDecision();
        if (type == null) {
            throw new InvalidPolicyException(
                    "policy '" + id + "' needs a 'decision' (allow, deny or require-approval)");
        }
        List<ToolPattern> tools =
                properties.getTools().stream().map(ToolPattern::of).toList();
        String when = properties.getWhen();
        Condition condition = when == null || when.isBlank() ? Condition.always() : SpelCondition.parse(when);
        return new Policy(id, tools, condition, decision(id, type, properties.getReason()), properties.getPriority());
    }

    private static Decision decision(String id, Decision.Type type, @Nullable String reason) {
        boolean hasReason = reason != null && !reason.isBlank();
        return switch (type) {
            case ALLOW -> Decision.allow();
            case DENY -> Decision.deny(hasReason ? reason : "Blocked by policy '" + id + "'");
            case REQUIRE_APPROVAL ->
                Decision.requireApproval(hasReason ? reason : "Policy '" + id + "' requires human approval");
        };
    }
}
