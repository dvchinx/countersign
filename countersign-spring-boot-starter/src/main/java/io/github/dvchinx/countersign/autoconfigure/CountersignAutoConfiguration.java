package io.github.dvchinx.countersign.autoconfigure;

import io.github.dvchinx.countersign.core.audit.InMemoryAuditSink;
import io.github.dvchinx.countersign.core.guard.ToolCallGuard;
import io.github.dvchinx.countersign.core.model.Decision;
import io.github.dvchinx.countersign.core.policy.Policy;
import io.github.dvchinx.countersign.core.policy.PolicyEngine;
import io.github.dvchinx.countersign.core.receipt.ArgumentsHmac;
import io.github.dvchinx.countersign.core.receipt.ReceiptChain;
import io.github.dvchinx.countersign.core.spi.AuditSink;
import io.github.dvchinx.countersign.core.spi.ReceiptSigner;
import io.github.dvchinx.countersign.springai.CountersignToolCallingBeanPostProcessor;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Auto-configuration for Countersign: builds the policy engine from {@code countersign.policies}, sets up signed
 * receipts and the audit sink, and guards every tool call made through Spring AI.
 *
 * <p>Every bean backs off when the application defines its own of the same type, so any part can be replaced: a
 * persistent {@link AuditSink}, a KMS-backed {@link ReceiptSigner}, or a {@link PolicyEngine} built in code.
 * {@link Policy} beans are added to the configured policies.
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
        PolicyEngine countersignPolicyEngine(CountersignProperties properties, ObjectProvider<Policy> policyBeans) {
            List<Policy> policies = new ArrayList<>(PolicyFactory.fromProperties(properties.getPolicies()));
            policyBeans.orderedStream().forEach(policies::add);
            return new PolicyEngine(policies, PolicyFactory.defaultDecision(properties));
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
                PolicyEngine engine, ReceiptChain receipts, AuditSink auditSink, CountersignProperties properties) {
            return new ToolCallGuard(
                    engine, receipts, auditSink, properties.getAudit().isStrict());
        }

        @Bean
        SmartInitializingSingleton countersignStartupReport(
                PolicyEngine engine, ReceiptChain receipts, AuditSink auditSink, CountersignProperties properties) {
            return () -> {
                LOG.info(
                        "Countersign is active: {} {}, default decision {}, receipts stream '{}', audit sink {}{}",
                        engine.policies().size(),
                        engine.policies().size() == 1 ? "policy" : "policies",
                        engine.defaultDecision().type(),
                        receipts.streamId(),
                        auditSink.getClass().getSimpleName(),
                        properties.getAudit().isStrict() ? " (strict)" : "");
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
