package io.github.dvchinx.countersign.autoconfigure;

import io.github.dvchinx.countersign.springai.micrometer.MicrometerDecisionListener;
import io.github.dvchinx.countersign.springai.micrometer.ObservationDecisionListener;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Publishes Countersign's decisions to Micrometer when the application has a {@link MeterRegistry} or an
 * {@link ObservationRegistry}, as it does with Spring Boot Actuator.
 *
 * <p>Both listeners are {@code DecisionListener} beans, which {@link CountersignAutoConfiguration} hands to the guard
 * together with any the application defines. Turn individual meters off with Spring Boot's
 * {@code management.metrics.enable.countersign=false}. See ADR 0011.
 */
@AutoConfiguration(
        afterName = {
            "org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration",
            "org.springframework.boot.micrometer.observation.autoconfigure.ObservationAutoConfiguration"
        })
@ConditionalOnBooleanProperty(name = "countersign.enabled", matchIfMissing = true)
public class CountersignObservabilityAutoConfiguration {

    /** Decision, evaluation and audit failure metrics. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "io.micrometer.core.instrument.MeterRegistry")
    @ConditionalOnBean(MeterRegistry.class)
    static class Metrics {

        @Bean
        @ConditionalOnMissingBean
        MicrometerDecisionListener countersignMetrics(MeterRegistry registry) {
            return new MicrometerDecisionListener(registry);
        }
    }

    /** The decision on each tool call's span. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "io.micrometer.observation.ObservationRegistry")
    @ConditionalOnBean(ObservationRegistry.class)
    static class Observations {

        @Bean
        @ConditionalOnMissingBean
        ObservationDecisionListener countersignObservations(ObservationRegistry registry) {
            return new ObservationDecisionListener(registry);
        }
    }
}
