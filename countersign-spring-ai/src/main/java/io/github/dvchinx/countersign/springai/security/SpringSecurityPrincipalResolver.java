package io.github.dvchinx.countersign.springai.security;

import io.github.dvchinx.countersign.core.spi.PrincipalResolver;
import java.util.Objects;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.security.authentication.AuthenticationTrustResolver;
import org.springframework.security.authentication.AuthenticationTrustResolverImpl;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;

/**
 * Reads who a tool call acts for from Spring Security, so receipts and {@code #principal} policies know the signed-in
 * user without the application passing it on every prompt.
 *
 * <p>Returns {@link Authentication#getName()} of the current authentication. Anonymous and unauthenticated users
 * count as unknown ({@code null}): an anonymous token's name is a placeholder that would look like a real user in an
 * audit trail.
 *
 * <p>The security context is bound to a thread. With {@code ChatClient.call()} the tools run on the request's thread
 * and the user is found. With {@code ChatClient.stream()}, Spring AI runs the tools on a Reactor worker thread, which
 * carries the user only if Reactor's automatic context propagation is on (in Spring Boot,
 * {@code spring.reactor.context-propagation=auto}); otherwise the call has no principal. Passing the principal in the
 * tool context always works.
 *
 * <p>The Countersign starter registers this resolver when Spring Security is on the classpath. Without the starter:
 *
 * <pre>{@code
 * ToolCallGuard.builder(engine, receipts, auditSink)
 *         .principalResolver(new SpringSecurityPrincipalResolver())
 *         .build();
 * }</pre>
 */
public final class SpringSecurityPrincipalResolver implements PrincipalResolver {

    private static final AuthenticationTrustResolver TRUST = new AuthenticationTrustResolverImpl();

    private final Supplier<SecurityContextHolderStrategy> strategy;

    /**
     * Reads the context through {@link SecurityContextHolder}'s current strategy, looked up on every call so that a
     * strategy set after startup is honoured.
     */
    public SpringSecurityPrincipalResolver() {
        this.strategy = SecurityContextHolder::getContextHolderStrategy;
    }

    /**
     * Reads the context through the given strategy, for applications that define their own
     * {@link SecurityContextHolderStrategy} bean.
     *
     * @param strategy the strategy that holds the security context
     */
    public SpringSecurityPrincipalResolver(SecurityContextHolderStrategy strategy) {
        Objects.requireNonNull(strategy, "strategy");
        this.strategy = () -> strategy;
    }

    @Override
    public @Nullable String currentPrincipal() {
        Authentication authentication = strategy.get().getContext().getAuthentication();
        return authentication != null && TRUST.isAuthenticated(authentication) ? authentication.getName() : null;
    }
}
