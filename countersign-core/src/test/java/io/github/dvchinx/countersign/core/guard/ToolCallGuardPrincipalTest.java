package io.github.dvchinx.countersign.core.guard;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dvchinx.countersign.core.model.Decision;
import io.github.dvchinx.countersign.core.model.JsonObject;
import io.github.dvchinx.countersign.core.model.Receipt;
import io.github.dvchinx.countersign.core.model.ToolInvocation;
import io.github.dvchinx.countersign.core.policy.Policy;
import io.github.dvchinx.countersign.core.policy.PolicyEngine;
import io.github.dvchinx.countersign.core.receipt.ArgumentsHmac;
import io.github.dvchinx.countersign.core.receipt.Ed25519ReceiptSigner;
import io.github.dvchinx.countersign.core.receipt.ReceiptChain;
import io.github.dvchinx.countersign.core.spi.PrincipalResolver;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ToolCallGuardPrincipalTest {

    private static final PolicyEngine ENGINE = new PolicyEngine(
            List.of(
                    Policy.builder("no-payments-for-interns")
                            .tools("payments.*")
                            .when(invocation -> "intern".equals(invocation.principal()))
                            .decision(Decision.deny("Interns cannot move money"))
                            .build(),
                    Policy.builder("payments-allowed")
                            .tools("payments.*")
                            .decision(Decision.allow())
                            .build()),
            Decision.deny("No policy allows this call"));

    private final List<Receipt> stored = new ArrayList<>();
    private final AtomicInteger decisionsHeard = new AtomicInteger();

    private ToolCallGuard guard(PrincipalResolver resolver) {
        try {
            ReceiptChain chain = ReceiptChain.start(
                    "stream-1",
                    new Ed25519ReceiptSigner(KeyPairGenerator.getInstance("Ed25519")
                            .generateKeyPair()
                            .getPrivate()),
                    new ArgumentsHmac(new byte[32]),
                    Clock.systemUTC());
            return ToolCallGuard.builder(ENGINE, chain, stored::add)
                    .principalResolver(resolver)
                    .listener(event -> decisionsHeard.incrementAndGet())
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static ToolInvocation transfer(String principal) {
        return new ToolInvocation(
                "inv-1",
                "payments.transfer",
                JsonObject.builder().put("amount", 50).build(),
                principal,
                null,
                Instant.now());
    }

    private static ToolInvocation transferWithoutPrincipal() {
        return ToolInvocation.of(
                "payments.transfer", JsonObject.builder().put("amount", 50).build());
    }

    @Test
    void recordsTheResolvedPrincipalWhenTheAdapterHasNone() {
        Verdict verdict = guard(() -> "alice").check(transferWithoutPrincipal());

        assertThat(verdict)
                .isInstanceOfSatisfying(
                        Verdict.Proceed.class,
                        proceed -> assertThat(proceed.receipt().principal()).isEqualTo("alice"));
    }

    @Test
    void evaluatesPoliciesAgainstTheResolvedPrincipal() {
        Verdict verdict = guard(() -> "intern").check(transferWithoutPrincipal());

        assertThat(verdict).isInstanceOfSatisfying(Verdict.Refuse.class, refuse -> {
            assertThat(refuse.reason()).isEqualTo("Interns cannot move money");
            assertThat(refuse.receipt().principal()).isEqualTo("intern");
        });
    }

    @Test
    void keepsAnExplicitPrincipalAndDoesNotAskTheResolver() {
        AtomicInteger asked = new AtomicInteger();

        Verdict verdict = guard(() -> {
                    asked.incrementAndGet();
                    return "intern";
                })
                .check(transfer("alice"));

        assertThat(verdict)
                .isInstanceOfSatisfying(
                        Verdict.Proceed.class,
                        proceed -> assertThat(proceed.receipt().principal()).isEqualTo("alice"));
        assertThat(asked).hasValue(0);
    }

    @Test
    void recordsNoPrincipalWhenTheResolverReturnsBlank() {
        Verdict verdict = guard(() -> "  ").check(transferWithoutPrincipal());

        assertThat(verdict)
                .isInstanceOfSatisfying(
                        Verdict.Proceed.class,
                        proceed -> assertThat(proceed.receipt().principal()).isNull());
    }

    @Test
    void refusesWithoutRunningPoliciesWhenTheResolverThrows() {
        Verdict verdict = guard(() -> {
                    throw new IllegalStateException("identity provider down");
                })
                .check(transferWithoutPrincipal());

        assertThat(verdict).isInstanceOfSatisfying(Verdict.Refuse.class, refuse -> {
            assertThat(refuse.decision()).isEqualTo(Decision.Type.DENY);
            assertThat(refuse.reason()).doesNotContain("identity provider");
            assertThat(refuse.receipt()).isNull();
        });
        assertThat(stored).isEmpty();
        assertThat(decisionsHeard).hasValue(0);
    }

    @Test
    void recordsInvalidArgumentsWithTheResolvedPrincipal() {
        Verdict verdict =
                guard(() -> "alice").refuseInvalidArguments("payments.transfer", "inv-2", null, null, Instant.now());

        assertThat(verdict)
                .isInstanceOfSatisfying(
                        Verdict.Refuse.class,
                        refuse -> assertThat(refuse.receipt().principal()).isEqualTo("alice"));
    }

    @Test
    void stillRecordsInvalidArgumentsWhenTheResolverThrows() {
        Verdict verdict = guard(() -> {
                    throw new IllegalStateException("identity provider down");
                })
                .refuseInvalidArguments("payments.transfer", "inv-3", null, null, Instant.now());

        assertThat(verdict).isInstanceOfSatisfying(Verdict.Refuse.class, refuse -> {
            assertThat(refuse.receipt()).isNotNull();
            assertThat(refuse.receipt().principal()).isNull();
        });
        assertThat(stored).hasSize(1);
    }
}
