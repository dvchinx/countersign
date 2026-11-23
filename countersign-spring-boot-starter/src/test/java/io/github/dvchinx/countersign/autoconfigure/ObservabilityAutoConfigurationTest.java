package io.github.dvchinx.countersign.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dvchinx.countersign.core.guard.ToolCallGuard;
import io.github.dvchinx.countersign.core.model.DecisionEvent;
import io.github.dvchinx.countersign.core.model.JsonObject;
import io.github.dvchinx.countersign.core.model.ToolInvocation;
import io.github.dvchinx.countersign.core.spi.DecisionListener;
import io.github.dvchinx.countersign.springai.micrometer.MicrometerDecisionListener;
import io.github.dvchinx.countersign.springai.micrometer.ObservationDecisionListener;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class ObservabilityAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    CountersignObservabilityAutoConfiguration.class, CountersignAutoConfiguration.class));

    private static void checkAQuery(ToolCallGuard guard) {
        guard.check(ToolInvocation.of(
                "db.query", JsonObject.builder().put("sql", "SELECT 1").build()));
    }

    @Test
    void countsDecisionsWhenTheApplicationHasAMeterRegistry() {
        runner.withBean(MeterRegistry.class, SimpleMeterRegistry::new).run(context -> {
            assertThat(context).hasSingleBean(MicrometerDecisionListener.class);

            checkAQuery(context.getBean(ToolCallGuard.class));

            assertThat(context.getBean(MeterRegistry.class)
                            .counter(
                                    MicrometerDecisionListener.DECISIONS,
                                    "tool",
                                    "db.query",
                                    "decision",
                                    "deny",
                                    "policy",
                                    "none",
                                    "basis",
                                    "default-decision",
                                    "outcome",
                                    "refuse",
                                    "approval",
                                    "none")
                            .count())
                    .isEqualTo(1);
        });
    }

    @Test
    void marksSpansWhenTheApplicationHasAnObservationRegistry() {
        runner.withBean(ObservationRegistry.class, ObservationRegistry::create)
                .run(context -> assertThat(context)
                        .hasSingleBean(ObservationDecisionListener.class)
                        .doesNotHaveBean(MicrometerDecisionListener.class));
    }

    @Test
    void addsNoListenerWithoutMicrometer() {
        runner.run(context -> assertThat(context).doesNotHaveBean(DecisionListener.class));
    }

    @Test
    void handsTheApplicationsOwnListenersToTheGuard() {
        runner.withUserConfiguration(OwnListener.class)
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .run(context -> {
                    checkAQuery(context.getBean(ToolCallGuard.class));

                    assertThat(OwnListener.events)
                            .singleElement()
                            .satisfies(event -> assertThat(event.proceeds()).isFalse());
                    assertThat(context.getBean(MeterRegistry.class)
                                    .find(MicrometerDecisionListener.DECISIONS)
                                    .counter())
                            .isNotNull();
                });
    }

    @Test
    void addsNothingWhenCountersignIsDisabled() {
        runner.withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withPropertyValues("countersign.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(DecisionListener.class));
    }

    @Configuration(proxyBeanMethods = false)
    static class OwnListener {

        static final List<DecisionEvent> events = new CopyOnWriteArrayList<>();

        @Bean
        DecisionListener alerts() {
            return events::add;
        }
    }
}
