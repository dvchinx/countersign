package io.github.dvchinx.countersign.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dvchinx.countersign.core.guard.ToolCallGuard;
import io.github.dvchinx.countersign.core.guard.Verdict;
import io.github.dvchinx.countersign.core.model.JsonObject;
import io.github.dvchinx.countersign.core.model.ToolInvocation;
import io.github.dvchinx.countersign.core.spi.PrincipalResolver;
import io.github.dvchinx.countersign.springai.security.SpringSecurityPrincipalResolver;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(OutputCaptureExtension.class)
class PrincipalAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CountersignAutoConfiguration.class))
            .withPropertyValues(
                    "countersign.policies[0].id=alice-only",
                    "countersign.policies[0].tools=payments.*",
                    "countersign.policies[0].when=#principal == 'alice'",
                    "countersign.policies[0].decision=allow");

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    private static Verdict transfer(ToolCallGuard guard) {
        return guard.check(ToolInvocation.of(
                "payments.transfer", JsonObject.builder().put("amount", 5).build()));
    }

    private static void signIn(String name) {
        SecurityContextHolder.getContext()
                .setAuthentication(UsernamePasswordAuthenticationToken.authenticated(name, null, List.of()));
    }

    @Test
    void usesTheSignedInUserForPoliciesAndReceiptsWhenSpringSecurityIsPresent(CapturedOutput output) {
        runner.run(context -> {
            assertThat(context).hasSingleBean(SpringSecurityPrincipalResolver.class);
            signIn("alice");

            assertThat(transfer(context.getBean(ToolCallGuard.class)))
                    .isInstanceOfSatisfying(
                            Verdict.Proceed.class,
                            proceed -> assertThat(proceed.receipt().principal()).isEqualTo("alice"));
            assertThat(output).contains("principal from tool context, then SpringSecurityPrincipalResolver");
        });
    }

    @Test
    void deniesWhenTheSignedInUserDoesNotMatchThePolicy() {
        runner.run(context -> {
            signIn("mallory");

            assertThat(transfer(context.getBean(ToolCallGuard.class))).isInstanceOf(Verdict.Refuse.class);
        });
    }

    @Test
    void backsOffWhenTheApplicationDefinesItsOwnResolver() {
        runner.withBean(PrincipalResolver.class, () -> () -> "alice").run(context -> {
            assertThat(context).doesNotHaveBean(SpringSecurityPrincipalResolver.class);

            assertThat(transfer(context.getBean(ToolCallGuard.class))).isInstanceOf(Verdict.Proceed.class);
        });
    }

    @Test
    void recordsNoPrincipalAndSaysSoAtStartupWithoutSpringSecurity(CapturedOutput output) {
        runner.withClassLoader(new FilteredClassLoader(SecurityContextHolder.class))
                .run(context -> {
                    assertThat(context).doesNotHaveBean(PrincipalResolver.class);

                    assertThat(transfer(context.getBean(ToolCallGuard.class))).isInstanceOf(Verdict.Refuse.class);
                    assertThat(output).contains("principal from tool context only");
                });
    }

    @Test
    void registersNoResolverWhenCountersignIsDisabled() {
        runner.withPropertyValues("countersign.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(PrincipalResolver.class));
    }
}
