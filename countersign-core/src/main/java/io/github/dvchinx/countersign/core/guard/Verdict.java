package io.github.dvchinx.countersign.core.guard;

import io.github.dvchinx.countersign.core.model.Decision;
import io.github.dvchinx.countersign.core.model.Receipt;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * What an adapter must do with a tool call, after {@link ToolCallGuard} has checked it.
 *
 * <p>Sealed with two outcomes only, so an adapter cannot misread a third state as permission: either the tool runs,
 * or the model gets a refusal.
 */
public sealed interface Verdict permits Verdict.Proceed, Verdict.Refuse {

    /**
     * The tool may run.
     *
     * @param receipt the receipt recorded for the call
     */
    record Proceed(Receipt receipt) implements Verdict {

        /**
         * Creates the verdict.
         *
         * @param receipt the receipt recorded for the call
         */
        public Proceed {
            Objects.requireNonNull(receipt, "receipt");
        }
    }

    /**
     * The tool must not run. The reason is safe to show to the model: it explains the rule, never the arguments,
     * and never internal error details.
     *
     * @param decision the kind of decision that led to the refusal
     * @param reason why the call was refused
     * @param receipt the receipt recorded for the call, or {@code null} if recording it is what failed
     */
    record Refuse(
            Decision.Type decision, String reason, @Nullable Receipt receipt) implements Verdict {

        /**
         * Creates the verdict.
         *
         * @param decision the kind of decision that led to the refusal
         * @param reason why the call was refused
         * @param receipt the receipt recorded for the call, or {@code null}
         */
        public Refuse {
            Objects.requireNonNull(decision, "decision");
            Objects.requireNonNull(reason, "reason");
        }
    }
}
