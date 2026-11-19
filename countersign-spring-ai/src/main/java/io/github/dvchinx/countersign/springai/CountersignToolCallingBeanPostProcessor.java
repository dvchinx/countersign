package io.github.dvchinx.countersign.springai;

import io.github.dvchinx.countersign.core.guard.ToolCallGuard;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.resolution.ToolCallbackResolver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;

/**
 * Guards every tool call in a Spring application, without changes to the application's tools.
 *
 * <p>Wraps the {@link ToolCallingManager} bean in a {@link GuardedToolCallingManager} and the
 * {@link ToolCallbackResolver} bean in a {@link GuardedToolCallbackResolver}. Spring AI's auto-configuration hands
 * that manager to the tool-calling advisor of every {@code ChatClient.Builder} it creates, so every tool executed
 * through those clients is checked, whichever way it was registered.
 *
 * <p>Declare it as a {@code static} bean so it is registered before the beans it wraps; the Countersign starter does
 * this. The {@link ToolCallGuard} is resolved lazily from the {@link ObjectProvider}, on the first tool call, so
 * registering this processor does not create the policy engine or load keys early.
 *
 * <p>Not covered: a {@code ChatClient} built by hand with its own {@code ToolCallingManager} (wrap it with
 * {@link GuardedToolCallingManager#guard}), and tool callbacks invoked directly by application code (wrap them with
 * {@link GuardedToolCallback#guard}).
 */
public final class CountersignToolCallingBeanPostProcessor implements BeanPostProcessor {

    private static final Logger LOG = LoggerFactory.getLogger(CountersignToolCallingBeanPostProcessor.class);

    private final ObjectProvider<ToolCallGuard> guard;

    /**
     * Creates the processor.
     *
     * @param guard provides the guard, resolved on first use
     */
    public CountersignToolCallingBeanPostProcessor(ObjectProvider<ToolCallGuard> guard) {
        this.guard = Objects.requireNonNull(guard, "guard");
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof ToolCallingManager manager && !(bean instanceof GuardedToolCallingManager)) {
            LOG.info(
                    "Countersign guards tool calls run by '{}' ({})",
                    beanName,
                    bean.getClass().getSimpleName());
            return new GuardedToolCallingManager(manager, guard::getObject);
        }
        if (bean instanceof ToolCallbackResolver resolver && !(bean instanceof GuardedToolCallbackResolver)) {
            LOG.debug("Countersign guards tools resolved by '{}'", beanName);
            return new GuardedToolCallbackResolver(resolver, guard::getObject);
        }
        return bean;
    }
}
