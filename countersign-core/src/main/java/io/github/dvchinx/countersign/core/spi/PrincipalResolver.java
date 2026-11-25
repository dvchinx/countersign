package io.github.dvchinx.countersign.core.spi;

import org.jspecify.annotations.Nullable;

/**
 * Tells Countersign who the agent is acting for, when the adapter that built the call does not know.
 *
 * <p>A receipt is meant to answer "who asked for this?", and policies can test {@code #principal}. Adapters pass the
 * principal they were given explicitly (in Spring AI, a value in the tool context); when they have none, the guard
 * asks this port instead, so an application that already knows its user, for example through Spring Security, does
 * not have to repeat it on every prompt. An explicit principal always wins over this port.
 *
 * <p>It is called once per tool call that has no explicit principal, on the thread that runs the tool, before the
 * policies are evaluated. Implementations that read thread-bound state (a security context) see the state of that
 * thread, which is not always the thread that received the user's request. Keep it fast and free of I/O: the policy
 * decision waits for it.
 *
 * <p>Returning {@code null} or a blank name means "unknown", and the call is evaluated and recorded without a
 * principal. Throwing refuses the call (fail closed): a policy that denies a given principal must not be bypassed
 * because the principal could not be read.
 */
@FunctionalInterface
public interface PrincipalResolver {

    /**
     * Returns who the current tool call acts for.
     *
     * @return the principal's name, or {@code null} if it is not known
     */
    @Nullable String currentPrincipal();
}
