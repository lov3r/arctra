package cn.bitcss.arctra.process;

import cn.bitcss.arctra.agent.AgentResult;

/**
 * Agent process handle for suspended/long-running executions.
 *
 * <p>AgentProcess represents a task execution that has exceeded the synchronous invocation
 * boundary and requires lifecycle management. It provides a handle for resuming suspended
 * executions and accessing final results.
 *
 * <h2>Lifecycle</h2>
 *
 * <p>Process lifecycle states:
 *
 * <ul>
 *   <li><b>RUNNING:</b> Active execution
 *   <li><b>WAITING:</b> Suspended, awaiting external event
 *   <li><b>COMPLETED:</b> Successfully finished
 *   <li><b>FAILED:</b> Terminated with error
 * </ul>
 *
 * <h2>Dynamic Materialization</h2>
 *
 * <p>AgentProcess is not created at invocation start. It materializes only when execution needs
 * to cross the synchronous invocation boundary (e.g., suspension for approval).
 *
 * <h2>Usage</h2>
 *
 * <pre>{@code
 * AgentResult result = agent.execute(request, context);
 *
 * if (result.isSuspended()) {
 *     AgentProcess process = result.process();
 *
 *     // Later, after external event (e.g., human approval)
 *     ContinuationSignal signal = new ApprovalSignal(true, "approved");
 *     AgentResult finalResult = process.resume(signal);
 * }
 * }</pre>
 *
 * <h2>Thread Safety</h2>
 *
 * <p>AgentProcess implementations must ensure that resume() is executed at most once per
 * suspension. Concurrent resume attempts on the same process should be rejected.
 *
 * @author lov3r
 * @since M4
 */
public interface AgentProcess {

  /**
   * Process identity.
   *
   * <p>Stable for process lifetime, unique within runtime.
   *
   * @return process ID
   */
  String id();

  /**
   * Current process status.
   *
   * @return process status
   */
  ProcessStatus status();

  /**
   * Resume suspended process.
   *
   * <p>Valid only when status is WAITING. Resumes execution with the provided continuation signal.
   *
   * <p>After resumption, process may:
   *
   * <ul>
   *   <li>Complete (status → COMPLETED)
   *   <li>Fail (status → FAILED)
   *   <li>Suspend again (status → WAITING)
   * </ul>
   *
   * @param signal continuation signal (e.g., approval decision)
   * @return result after resumption (may be suspended again)
   * @throws IllegalStateException if process is not in WAITING state
   * @throws IllegalStateException if concurrent resume detected
   * @throws NullPointerException if signal is null
   */
  AgentResult resume(ContinuationSignal signal);

  /**
   * Get final result.
   *
   * <p>Valid only when status is COMPLETED.
   *
   * @return final result
   * @throws IllegalStateException if process is not completed
   */
  AgentResult result();
}
