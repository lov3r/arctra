package cn.bitcss.arctra.execution;

/**
 * Execution event listener for projection and observation.
 *
 * <p>Receives notifications of already-true execution facts. Listeners project these facts into
 * audit trails, metrics, logs, traces, or other observation systems.
 *
 * <h2>Authority Boundaries</h2>
 *
 * <p>ExecutionEventListener is for <strong>projection and observation only</strong>. It MUST NOT:
 *
 * <ul>
 *   <li>Create domain truth - domain facts become true BEFORE events are emitted
 *   <li>Be recovery authority - {@link cn.bitcss.arctra.checkpoint.SuspensionCheckpoint} remains
 *       recovery authority
 *   <li>Decide CHECK A validity - checkpoint validation remains checkpoint responsibility
 *   <li>Decide CHECK B success - CAS outcomes remain checkpoint responsibility
 *   <li>Determine resumability - checkpoint existence determines resumability
 *   <li>Mutate checkpoint semantics - checkpoint lifecycle is independent of listeners
 * </ul>
 *
 * <h2>Failure Semantics</h2>
 *
 * <p>Listener projection failure does NOT invalidate the already-true domain fact. For example:
 *
 * <ul>
 *   <li>If a checkpoint is successfully created (SUSPENDED = true), listener failure does not make
 *       the process non-resumable
 *   <li>If CHECK B deleteIfVersion succeeds (COMPLETED = true), listener failure does not make the
 *       process incomplete
 * </ul>
 *
 * <p>Projection failure means the observation/audit trail has a gap, not that the domain operation
 * failed.
 *
 * <h2>Implementation Notes</h2>
 *
 * <p>Listeners should be stateless or thread-safe. The same listener instance may receive events
 * from multiple concurrent executions.
 *
 * <p>Listeners should avoid blocking operations. Long-running projections should use asynchronous
 * mechanisms outside the listener invocation.
 *
 * @author lov3r
 * @since M6-T2C
 */
@FunctionalInterface
public interface ExecutionEventListener {

  /**
   * Handle execution event.
   *
   * <p>Receives notification of an already-true domain fact. This method is invoked synchronously
   * after the domain fact becomes true.
   *
   * <p>Exceptions thrown by this method do NOT roll back domain transitions. The domain fact
   * remains true. Exception handling depends on the event dispatch mechanism (typically isolated to
   * prevent one listener failure from suppressing others).
   *
   * @param event the execution event (non-null)
   */
  void onEvent(ExecutionEvent event);
}
