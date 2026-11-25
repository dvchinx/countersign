package io.github.dvchinx.countersign.springai.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.core.context.SecurityContextImpl;

class SpringSecurityPrincipalResolverTest {

    private final SpringSecurityPrincipalResolver resolver = new SpringSecurityPrincipalResolver();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void returnsTheSignedInUsersName() {
        SecurityContextHolder.getContext()
                .setAuthentication(UsernamePasswordAuthenticationToken.authenticated("alice", null, List.of()));

        assertThat(resolver.currentPrincipal()).isEqualTo("alice");
    }

    @Test
    void returnsNothingWhenNobodyIsSignedIn() {
        assertThat(resolver.currentPrincipal()).isNull();
    }

    @Test
    void doesNotTreatAnAnonymousUserAsAPrincipal() {
        SecurityContextHolder.getContext()
                .setAuthentication(new AnonymousAuthenticationToken(
                        "key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));

        assertThat(resolver.currentPrincipal()).isNull();
    }

    @Test
    void doesNotTreatAnUnauthenticatedTokenAsAPrincipal() {
        SecurityContextHolder.getContext()
                .setAuthentication(UsernamePasswordAuthenticationToken.unauthenticated("mallory", "guess"));

        assertThat(resolver.currentPrincipal()).isNull();
    }

    @Test
    void readsThroughTheGivenStrategy() {
        SecurityContextHolderStrategy custom = new SecurityContextHolderStrategy() {
            @Override
            public void clearContext() {}

            @Override
            public SecurityContext getContext() {
                return new SecurityContextImpl(
                        UsernamePasswordAuthenticationToken.authenticated("bob", null, List.of()));
            }

            @Override
            public void setContext(SecurityContext context) {}

            @Override
            public SecurityContext createEmptyContext() {
                return new SecurityContextImpl();
            }
        };

        assertThat(new SpringSecurityPrincipalResolver(custom).currentPrincipal())
                .isEqualTo("bob");
    }
}
