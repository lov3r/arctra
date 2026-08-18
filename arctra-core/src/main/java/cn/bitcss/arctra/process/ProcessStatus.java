package cn.bitcss.arctra.process;

/**
 * Process lifecycle status.
 *
 * <p>Defines the possible states of an AgentProcess.
 *
 * <h2>State Transitions</h2>
 *
 * <p>Valid transitions:
 *
 * <ul>
 *   <li>RUNNING → WAITING (suspension)
 *   <li>WAITING → RUNNING (resumption)
 *   <li>RUNNING → COMPLETED (success)
 *   <li>RUNNING → FAILED (error)
 *   <li>WAITING → FAILED (resume failure)
 * </ul>
 *
 * <p>Terminal states (COMPLETED, FAILED) cannot transition to other states.
 *
 * @author lov3r
 * @since M4
 */
public enum ProcessStatus {

  /**
   * Active execution.
   *
   * <p>Process is currently executing.
   */
  RUNNING,

  /**
   * Suspended, awaiting external event.
   *
   * <p>Process has suspended and is waiting for external continuation signal (e.g., human
   * approval).
   */
  WAITING,

  /**
   * Successfully finished.
   *
   * <p>Terminal state. Process has completed successfully and final result is available.
   */
  COMPLETED,

  /**
   * Terminated with error.
   *
   * <p>Terminal state. Process execution failed.
   */
  FAILED
}
