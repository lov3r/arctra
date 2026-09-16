package cn.bitcss.arctra.runtime.react.protocol;

import cn.bitcss.arctra.checkpoint.PendingToolCall;
import cn.bitcss.arctra.evidence.Evidence;
import cn.bitcss.arctra.process.ContinuationSignal;
import cn.bitcss.arctra.runtime.RuntimeBinding;
import cn.bitcss.arctra.runtime.react.durable.RecoveryClassificationResult;
import cn.bitcss.arctra.runtime.react.tool.ToolObservationContext;
import java.util.List;

/**
 * Resumed execution handler boundary.
 *
 * <p>Package-private interface for provider-specific resumed execution mechanics.
 *
 * <p>This boundary allows durable orchestration (DurableResumeCoordinator) to remain
 * provider-independent while delegating execution to Spring AI or other providers.
 *
 * <p><strong>Responsibility</strong>: Provider execution mechanics only
 * (protocol reconstruction, tool execution, model continuation).
 *
 * <p><strong>NOT responsible for</strong>: CHECK A/B, checkpoint transitions,
 * durable lifecycle events, runtime binding resolution.
 *
 * @author lov3r
 */
public interface ResumedExecutionHandler {

  /**
   * Execute resumed execution and return mechanism outcome.
   *
   * <p>Performs provider-specific execution:
   *
   * <ol>
   *   <li>Protocol reconstruction (approved or rejected)
   *   <li>Tool execution (if approved) - M6-T5: mixed physical/recovered
   *   <li>Model continuation
   *   <li>Detection of completion or new governance suspension
   * </ol>
   *
   * <p>M6-T5: classifications parameter provides recovery execution plan for each operation,
   * enabling mixed batches with physical execution and recovered results.
   *
   * <p>Returns execution mechanism outcome. Durable state transitions remain caller responsibility.
   *
   * @param pendingBatch pending tool calls from checkpoint
   * @param binding runtime binding (definition + context)
   * @param checkpointEvidences accumulated evidences from checkpoint
   * @param signal continuation signal (approved/rejected)
   * @param observationContext tool observation context for event emission
   * @param classifications recovery classifications (null for same-incarnation resume, non-null for
   *     cross-incarnation recovery)
   * @return execution outcome (model completed or governance suspended)
   * @since M6-T5 classifications parameter
   */
  ResumedExecutionOutcome executeResume(
      List<PendingToolCall> pendingBatch,
      RuntimeBinding binding,
      List<Evidence> checkpointEvidences,
      ContinuationSignal signal,
      ToolObservationContext observationContext,
      List<RecoveryClassificationResult> classifications);

  /**
   * Persist final completed assistant message to ChatMemory.
   *
   * <p>Called by orchestration layer after CHECK B deleteIfVersion succeeds.
   *
   * <p>This is a narrow post-commit action to maintain the current crash window semantics:
   * CHECK B → COMPLETED → ChatMemory persistence.
   *
   * @param context execution context with session ID
   * @param content final assistant content
   */
  void persistCompletedAssistant(
      cn.bitcss.arctra.agent.AgentExecutionContext context, String content);
}
