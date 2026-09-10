package cn.bitcss.arctra.runtime;

import cn.bitcss.arctra.process.ContinuationSignal;

/**
 * Strategy for resuming a suspended process.
 *
 * <p>Package-private abstraction that encapsulates resume preparation and execution logic. Supports
 * both ephemeral (M4 in-memory) and durable (M5 checkpoint-backed) suspension strategies.
 *
 * <h2>Two-Phase Resume Protocol</h2>
 *
 * <ol>
 *   <li><strong>prepare(signal)</strong> - Lightweight preparation that creates an immutable
 *       ResumeAttempt. Must not perform I/O or side effects.
 *   <li><strong>Local CAS</strong> - DefaultAgentProcess transitions WAITING → RUNNING (single
 *       consumer)
 *   <li><strong>attempt.execute()</strong> - Actual execution with potential external side effects
 * </ol>
 *
 * <p>This ensures external side effects only occur after local process ownership is established via
 * CAS.
 *
 * @author lov3r
 * @since M5-T4
 */
interface ResumeStrategy {

  /**
   * Prepare resume attempt.
   *
   * <p>Lightweight preparation phase - must NOT perform I/O, load checkpoints, resolve bindings, or
   * execute tools. Creates an immutable {@link ResumeAttempt} that captures parameters for later
   * execution.
   *
   * <p>This method may be called concurrently by multiple threads attempting to resume the same
   * process. The actual execution occurs only after local CAS succeeds.
   *
   * @param signal continuation signal
   * @return immutable resume attempt ready for execution
   */
  ResumeAttempt prepare(ContinuationSignal signal);
}
