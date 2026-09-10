package cn.bitcss.arctra.runtime;

import cn.bitcss.arctra.agent.AgentResult;

/**
 * Immutable resume attempt ready for execution.
 *
 * <p>Package-private abstraction representing a prepared resume attempt. Created during the
 * lightweight {@link ResumeStrategy#prepare} phase and executed after local CAS succeeds.
 *
 * <p>Instances are immutable and represent a single execution attempt. Multiple threads may prepare
 * attempts concurrently, but only the winning CAS thread executes its attempt.
 *
 * @author lov3r
 * @since M5-T4
 */
interface ResumeAttempt {

  /**
   * Execute the resume attempt.
   *
   * <p>Called after local process CAS succeeds (WAITING → RUNNING). This is where actual execution
   * occurs - tool calls, model invocations, checkpoint operations, etc.
   *
   * <p>For ephemeral strategies, executes the captured continuation function. For durable
   * strategies, delegates to {@link DurableExecutionEngine#resumeProcess} which performs the
   * complete durable recovery pipeline.
   *
   * @return execution result (may be completed or suspended again)
   */
  AgentResult execute();
}
