package io.github.dvchinx.countersign.springai.micrometer;

import io.github.dvchinx.countersign.core.model.DecisionEvent;
import io.github.dvchinx.countersign.core.model.Receipt;
import io.github.dvchinx.countersign.core.spi.DecisionListener;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.Objects;
import org.springframework.ai.tool.observation.ToolCallingObservationContext;

/**
 * Adds Countersign's decision to the observation Spring AI opens around each tool call, so a trace shows, on the tool
 * call's own span, whether Countersign let it run and which receipt records it.
 *
 * <p>Spring AI's {@code ToolCallingManager} runs every tool inside a {@code spring.ai.tool} observation, and the guard
 * decides on that same thread. This listener finds that observation and adds {@code countersign.decision},
 * {@code countersign.outcome}, {@code countersign.policy} and {@code countersign.receipt} (stream id and sequence) as
 * high-cardinality key values: they reach traces but never change the tags of Spring AI's own metrics. If the current
 * observation is not the call of the same tool, for example because the tool was called outside Spring AI's tool
 * loop, nothing is added. Thread-safe.
 */
public final class ObservationDecisionListener implements DecisionListener {

    private final ObservationRegistry registry;

    /**
     * Creates a listener.
     *
     * @param registry the registry Spring AI's tool calling uses
     */
    public ObservationDecisionListener(ObservationRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @Override
    public void onDecision(DecisionEvent event) {
        Observation observation = registry.getCurrentObservation();
        if (observation == null
                || !(observation.getContext() instanceof ToolCallingObservationContext tool)
                || !event.tool().equals(tool.getToolDefinition().name())) {
            return;
        }
        Receipt receipt = event.receipt();
        observation
                .highCardinalityKeyValue("countersign.decision", MicrometerDecisionListener.tag(event.decision()))
                .highCardinalityKeyValue("countersign.outcome", event.proceeds() ? "proceed" : "refuse")
                .highCardinalityKeyValue("countersign.policy", event.policyId() == null ? "none" : event.policyId())
                .highCardinalityKeyValue(
                        "countersign.receipt",
                        receipt == null ? "none" : receipt.streamId() + "#" + receipt.sequence());
    }
}
