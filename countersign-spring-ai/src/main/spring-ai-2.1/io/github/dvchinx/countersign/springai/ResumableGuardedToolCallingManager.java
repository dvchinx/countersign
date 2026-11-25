package io.github.dvchinx.countersign.springai;

import io.github.dvchinx.countersign.core.guard.ToolCallGuard;
import java.util.function.Supplier;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallDecisions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;

/**
 * A {@link GuardedToolCallingManager} that can also resume a batch of tool calls that Spring AI 2.1 paused for a
 * person's confirmation.
 *
 * <p>Spring AI resumes a paused batch through
 * {@link ToolCallingManager#executeToolCalls(Prompt, ChatResponse, ToolCallDecisions)}, whose default throws. This
 * class guards the prompt's tools exactly as the two-argument method does, then hands the decisions to the wrapped
 * manager. A call the person denied does not run, so Countersign never sees it. A call the person allowed still runs
 * through a guarded callback: Countersign evaluates it, records a receipt and refuses it if a policy says so. A
 * person's "allow" never skips a policy, and it does not count as an approval for a {@code require-approval} policy,
 * which still goes to Countersign's {@code ApprovalGateway}.
 *
 * <p>This file lives in {@code src/main/spring-ai-2.1} and is compiled against Spring AI 2.1 in a separate step,
 * while the rest of the module is compiled against the 2.0 baseline (ADR 0013). Only
 * {@link GuardedToolCallingManager#create} instantiates it, by name, and only when {@code ToolCallDecisions} exists,
 * so Spring AI 2.0 never loads it.
 */
final class ResumableGuardedToolCallingManager extends GuardedToolCallingManager {

    ResumableGuardedToolCallingManager(ToolCallingManager delegate, Supplier<ToolCallGuard> guard) {
        super(delegate, guard);
    }

    @Override
    public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse chatResponse, ToolCallDecisions decisions) {
        return getDelegate().executeToolCalls(guarded(prompt), chatResponse, decisions);
    }
}
