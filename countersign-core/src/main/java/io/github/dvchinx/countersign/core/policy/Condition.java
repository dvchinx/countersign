package io.github.dvchinx.countersign.core.policy;

import io.github.dvchinx.countersign.core.model.ToolInvocation;

/**
 * Decides whether a policy applies to an invocation, once its tool pattern has matched.
 *
 * <p>The core defines only this interface. The expression language lives in an adapter (the Spring module uses
 * sandboxed SpEL), so the core stays framework-free and conditions can also be plain lambdas in tests or code.
 *
 * <p>Implementations must be deterministic, must not block on I/O, and must be safe to call from several threads
 * at once. They may throw: the engine treats any exception as a failure and denies the call.
 */
@FunctionalInterface
public interface Condition {

    /**
     * Tells whether the policy applies to this invocation.
     *
     * @param invocation the invocation being evaluated
     * @return {@code true} if the policy applies
     */
    boolean matches(ToolInvocation invocation);

    /**
     * Returns a condition that always matches, for policies that apply to every call of their tools.
     *
     * @return a condition that always matches
     */
    static Condition always() {
        return invocation -> true;
    }
}
