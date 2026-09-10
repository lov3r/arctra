package cn.bitcss.arctra.runtime;

import cn.bitcss.arctra.agent.AgentResult;
import cn.bitcss.arctra.process.ContinuationSignal;

/**
 * Durable execution engine capability.
 *
 * <p>Optional specialization of {@link AgentExecutionEngine} that supports checkpoint-backed
 * durable recovery. Engines implementing this interface can suspend execution durably and resume
 * across runtime/JVM boundaries.
 *
 * <p><strong>This is a capability specialization, not a competing SPI.</strong> Engines may
 * implement either {@link AgentExecutionEngine} (ephemeral only) or this interface (durable
 * capable). The primary backend contract remains {@link AgentExecutionEngine} - this interface
 * merely adds an optional recovery capability.
 *
 * <h2>Unified Durable Resume Pipeline</h2>
 *
 * <p>The {@link #resumeProcess} method is the ONE authoritative durable recovery pipeline. It owns
 * the complete orchestration:
 *
 * <ol>
 *   <li>Load checkpoint from store
 *   <li>CHECK A: Validate checkpoint version (fencing token)
 *   <li>Resolve runtime binding (definition + context)
 *   <li>Protocol reconstruction and tool execution
 *   <li>Model continuation
 *   <li>CHECK B: Conditional checkpoint transition (completion or re-suspension)
 *   <li>Return execution result
 * </ol>
 *
 * <p>Both local {@link cn.bitcss.arctra.process.AgentProcess#resume} and cross-runtime {@link
 * AgentRuntime#resumeProcess} converge on this method - there is no duplicate durable
 * orchestration logic.
 *
 * <h2>Execution Authority</h2>
 *
 * <p>The calling {@code DurableExecutionEngine} is the execution authority. Runtime binding
 * provides the agent definition and execution context, but execution uses the calling engine's
 * tools, model, policies, and infrastructure.
 *
 * @author lov3r
 * @since M5-T4
 */
public interface DurableExecutionEngine extends AgentExecutionEngine {

  /**
   * Resume durable suspended process.
   *
   * <p>Cross-runtime recovery entry point. Loads checkpoint, performs validation and recovery
   * orchestration, and returns execution result. This method owns the complete durable resume
   * pipeline including CHECK A/B and all orchestration logic.
   *
   * <p><strong>CHECK A (pre-execution validation):</strong> Validates checkpoint exists and version
   * matches expected value (fencing token). Throws if checkpoint missing or version stale - zero
   * tool side effects occur on CHECK A failure.
   *
   * <p><strong>CHECK B (post-execution CAS):</strong> Conditional checkpoint transition using
   * version-based CAS. Completion deletes checkpoint; re-suspension replaces with incremented
   * version. Throws if concurrent modification detected.
   *
   * <p><strong>Concurrency:</strong> Multiple concurrent calls with same processId/version may both
   * pass CHECK A and execute tools (at-least-once semantics). Only one CHECK B transition succeeds
   * - loser receives {@code CheckpointTransitionConflictException}.
   *
   * @param processId stable process identifier
   * @param checkpointVersion suspension episode version (fencing token for CHECK A/B)
   * @param signal continuation signal (approval/rejection decision)
   * @return execution result (may be suspended again with incremented version)
   * @throws CheckpointNotFoundException if checkpoint not found (CHECK A failure)
   * @throws StaleCheckpointException if checkpoint version mismatch (CHECK A failure)
   * @throws CheckpointTransitionConflictException if concurrent modification detected (CHECK B
   *     failure)
   * @throws ResumePreparationException if runtime binding resolution fails
   */
  AgentResult resumeProcess(
      String processId, long checkpointVersion, ContinuationSignal signal);
}
