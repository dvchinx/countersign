package io.github.dvchinx.countersign.autoconfigure;

import io.github.dvchinx.countersign.core.audit.InMemoryAuditSink;
import io.github.dvchinx.countersign.core.guard.ToolCallGuard;
import io.github.dvchinx.countersign.core.model.Decision;
import io.github.dvchinx.countersign.core.policy.Policy;
import io.github.dvchinx.countersign.core.policy.PolicyEngine;
import io.github.dvchinx.countersign.core.receipt.ArgumentsHmac;
import io.github.dvchinx.countersign.core.receipt.ReceiptChain;
import io.github.dvchinx.countersign.core.spi.ApprovalGateway;
import io.github.dvchinx.countersign.core.spi.AuditSink;
import io.github.dvchinx.countersign.core.spi.DecisionListener;
import io.github.dvchinx.countersign.core.spi.PrincipalResolver;
import io.github.dvchinx.countersign.core.spi.ReceiptSigner;
import io.github.dvchinx.countersign.springai.CountersignToolCallingBeanPostProcessor;
import io.github.dvchinx.countersign.springai.annotation.RequiresApprovalPolicies;
import io.github.dvchinx.countersign.springai.security.SpringSecurityPrincipalResolver;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.core.context.SecurityContextHolderStrategy;

/**
 * Auto-configuration for Countersign: builds the policy engine from {@code countersign.policies}, sets up signed
 * receipts and the audit sink, and guards every tool call made through Spring AI.
 *
 * <p>Every bean backs off when the application defines its own of the same type, so any part can be replaced: a
 * persistent {@link AuditSink}, a KMS-backed {@link ReceiptSigner}, or a {@link PolicyEngine} built in code.
 * {@link Policy} beans are added to the configured policies, and every {@link DecisionListener} bean hears about each
 * decision.
 *
 * <p>When Spring Security is on the classpath, the signed-in user becomes the principal of every tool call that does
 * not carry one in its tool context ({@link SpringSecurityPrincipalResolver}); a {@link PrincipalResolver} bean of the
 * application's own replaces it.
 *
 * <p>With {@code countersign.enabled=false} nothing is guarded, and a warning says so at startup.
 */
@AutoConfiguration
public class CountersignAutoConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(CountersignAutoConfiguration.class);

    /** The beans that guard tool calls. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnBooleanProperty(name = "countersign.enabled", matchIfMissing = true)
    @EnableConfigurationProperties(CountersignProperties.class)
    static class Enabled {

        @Bean
        static CountersignToolCallingBeanPostProcessor countersignToolCallingBeanPostProcessor(
                ObjectProvider<ToolCallGuard> guard) {
            return new CountersignToolCallingBeanPostProcessor(guard);
        }

        @Bean
        @ConditionalOnMissingBean
        PolicyEngine countersignPolicyEngine(
                CountersignProperties properties, ObjectProvider<Policy> policyBeans, ApplicationContext context) {
            List<Policy> policies = new ArrayList<>(PolicyFactory.fromProperties(properties.getPolicies()));
            policyBeans.orderedStream().forEach(policies::add);
            return new PolicyEngine(
                    policies, annotatedPolicies(properties, context), PolicyFactory.defaultDecision(properties));
        }

        /** Mandatory policies from @RequiresApproval, found in the application's packages and any configured ones. */
        private static List<Policy> annotatedPolicies(CountersignProperties properties, ApplicationContext context) {
            Set<String> packages = new LinkedHashSet<>(properties.getAnnotationPackages());
            if (AutoConfigurationPackages.has(context)) {
                packages.addAll(AutoConfigurationPackages.get(context));
            }
            ClassLoader classLoader = context.getClassLoader();
            return RequiresApprovalPolicies.scan(
                    packages, classLoader != null ? classLoader : CountersignAutoConfiguration.class.getClassLoader());
        }

        @Bean
        @ConditionalOnMissingBean
        ReceiptSigner countersignReceiptSigner(CountersignProperties properties) {
            return ReceiptKeys.signer(properties.getReceipts().getSigningKey());
        }

        @Bean
        @ConditionalOnMissingBean
        ArgumentsHmac countersignArgumentsHmac(CountersignProperties properties) {
            return ReceiptKeys.argumentsHmac(properties.getReceipts().getArgsHmacKey());
        }

        @Bean
        @ConditionalOnMissingBean
        AuditSink countersignAuditSink(CountersignProperties properties) {
            return new InMemoryAuditSink(properties.getAudit().getInMemoryCapacity());
        }

        @Bean
        @ConditionalOnMissingBean
        ReceiptChain countersignReceiptChain(
                CountersignProperties properties,
                ReceiptSigner signer,
                ArgumentsHmac argumentsHmac,
                Environment environment) {
            String streamId = properties.getReceipts().getStreamId();
            if (streamId == null || streamId.isBlank()) {
                // One stream per running instance: two instances must never extend the same chain.
                streamId = environment.getProperty("spring.application.name", "countersign") + "-"
                        + HexFormat.of().toHexDigits(ThreadLocalRandom.current().nextLong());
            }
            return ReceiptChain.start(streamId, signer, argumentsHmac, Clock.systemUTC());
        }

        @Bean
        @ConditionalOnMissingBean
        ToolCallGuard countersignToolCallGuard(
                PolicyEngine engine,
                ReceiptChain receipts,
                AuditSink auditSink,
                ObjectProvider<ApprovalGateway> approvals,
                ObjectProvider<DecisionListener> listeners,
                ObjectProvider<PrincipalResolver> principals,
                CountersignProperties properties) {
            ToolCallGuard.Builder guard = ToolCallGuard.builder(engine, receipts, auditSink)
                    .approvals(approvals.getIfUnique())
                    .principalResolver(principals.getIfUnique())
                    .approvalTimeout(properties.getApproval().getTimeout())
                    .strictAudit(properties.getAudit().isStrict());
            listeners.orderedStream().forEach(guard::listener);
            return guard.build();
        }

        @Bean
        SmartInitializingSingleton countersignStartupReport(
                PolicyEngine engine,
                ReceiptChain receipts,
                AuditSink auditSink,
                ObjectProvider<ApprovalGateway> approvals,
                ObjectProvider<PrincipalResolver> principals,
                CountersignProperties properties) {
            return () -> {
                ApprovalGateway gateway = approvals.getIfUnique();
                PrincipalResolver principalResolver = principals.getIfUnique();
                int total =
                        engine.policies().size() + engine.mandatoryPolicies().size();
                LOG.info(
                        "Countersign is active: {} {} ({} from @RequiresApproval), default decision {}, receipts"
                                + " stream '{}', audit sink {}{}, approvals {}, principal from {}",
                        total,
                        total == 1 ? "policy" : "policies",
                        engine.mandatoryPolicies().size(),
                        engine.defaultDecision().type(),
                        receipts.streamId(),
                        auditSink.getClass().getSimpleName(),
                        properties.getAudit().isStrict() ? " (strict)" : "",
                        gateway == null
                                ? "none"
                                : gateway.getClass().getSimpleName() + " (timeout "
                                        + properties.getApproval().getTimeout() + ")",
                        principalResolver == null
                                ? "tool context only"
                                : "tool context, then "
                                        + principalResolver.getClass().getSimpleName());
                boolean approvalNeeded = engine.defaultDecision().type() == Decision.Type.REQUIRE_APPROVAL
                        || Stream.concat(engine.policies().stream(), engine.mandatoryPolicies().stream())
                                .anyMatch(policy -> policy.decision().type() == Decision.Type.REQUIRE_APPROVAL);
                if (approvalNeeded && gateway == null) {
                    LOG.warn("Some calls require approval, but no ApprovalGateway bean is defined: they will all be"
                            + " denied. Define one, for example an InMemoryApprovalGateway behind an approval screen.");
                }
                if (engine.defaultDecision().type() != Decision.Type.DENY) {
                    LOG.warn(
                            "Countersign default decision is {}: tool calls that no policy matches are not denied",
                            engine.defaultDecision().type());
                }
                if (auditSink instanceof InMemoryAuditSink) {
                    LOG.warn("Countersign keeps receipts in memory only; they are lost on restart."
                            + " Define an AuditSink bean to store them.");
                }
            };
        }
    }

    /** The signed-in Spring Security user as the principal of each call. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnBooleanProperty(name = "countersign.enabled", matchIfMissing = true)
    @ConditionalOnClass(name = "org.springframework.security.core.context.SecurityContextHolder")
    static class SpringSecurityPrincipal {

        @Bean
        @ConditionalOnMissingBean
        PrincipalResolver countersignPrincipalResolver(ObjectProvider<SecurityContextHolderStrategy> strategy) {
            SecurityContextHolderStrategy custom = strategy.getIfUnique();
            return custom == null ? new SpringSecurityPrincipalResolver() : new SpringSecurityPrincipalResolver(custom);
        }
    }

    /** Says loudly that nothing is guarded. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnBooleanProperty(name = "countersign.enabled", havingValue = false)
    static class Disabled {

        @Bean
        SmartInitializingSingleton countersignDisabledWarning() {
            return () -> LOG.warn("Countersign is disabled (countersign.enabled=false): tool calls are NOT guarded");
        }
    }
}
