package cn.bitcss.arctra.controlplane;

import cn.bitcss.arctra.checkpoint.ContinuationDisposition;
import cn.bitcss.arctra.process.ContinuationSignal;
import java.util.List;
import java.util.Optional;

/**
 * Recovery Control Plane for operational discovery and operator-driven recovery.
 *
 * <h2>M7: Recovery Control Plane</h2>
 *
 * <p>The Control Plane provides:
 *
 * <ol>
 *   <li><strong>Discovery</strong> — list/inspect current recoverable continuations
 *   <li><strong>Operator Recovery</strong> — trigger M6 durable recovery for a selected process
 * </ol>
 *
 * <p>The Control Plane is an <strong>operations view</strong> over M6 Durable Execution Kernel. It
 * does NOT implement another execution engine. It delegates recovery into existing M6 CHECK A/CHECK
 * B semantics.
 *
 * <h2>Authority Model</h2>
 *
 * <p>The Control Plane is <strong>NOT process truth</strong>. It reads from authoritative {@link
 * cn.bitcss.arctra.checkpoint.CheckpointStore} and delegates recovery to {@link
 * cn.bitcss.arctra.core.AgentRuntime}.
 *
 * <ul>
 *   <li>CheckpointStore remains continuation authority
 *   <li>M6 CHECK A/CHECK B remain recovery safety boundaries
 *   <li>Control Plane exposes read models and operator entry points
 * </ul>
 *
 * <h2>Discovery Semantics</h2>
 *
 * <p>Discovery operations return <strong>snapshots</strong> of current recoverable continuations.
 * Results may become stale immediately after return. This is safe: stale descriptors are rejected
 * by M6 CAS semantics during recovery.
 *
 * <h2>Operator Recovery Flow</h2>
 *
 * <pre>
 * 1. Operator discovers continuations → listContinuations()
 * 2. Operator selects one → RecoverableContinuation(processId, version, ...)
 * 3. Operator triggers recovery → resumeContinuation(processId, version, signal)
 * 4. Control Plane delegates → M6 DurableExecutionEngine.resumeProcess(...)
 * 5. M6 CHECK A validates version/binding
 * 6. M6 executes continuation
 * 7. M6 CHECK B commits transition
 * </pre>
 *
 * <h2>Version-Aware Recovery</h2>
 *
 * <p>Operator recovery MUST provide checkpoint version for safe CAS semantics. If the checkpoint
 * was advanced/deleted since discovery, M6 CHECK A rejects the stale recovery attempt.
 *
 * <h2>Cross-Instance Discovery</h2>
 *
 * <p>Multiple JVMs sharing the same durable store (JDBC) can discover the same continuations. This
 * is acceptable. Concurrent recovery attempts are resolved by M6 CAS, not by control plane
 * ownership claims.
 *
 * <h2>NOT Distributed Orchestration</h2>
 *
 * <p>M7 does NOT provide:
 *
 * <ul>
 *   <li>Process ownership / worker ownership
 *   <li>Distributed locks / leases / fencing
 *   <li>Heartbeats / liveness detection
 *   <li>Automatic scheduling / leader election
 * </ul>
 *
 * <p>These belong to future distributed operations track if required.
 *
 * <h2>Thread Safety</h2>
 *
 * <p>Implementations must be thread-safe for concurrent discovery and recovery operations.
 *
 * @author lov3r
 * @since M7
 */
public interface RecoveryControlPlane {

  /**
   * List all current recoverable continuations, ordered by update time (oldest first).
   *
   * <p>Returns a <strong>snapshot</strong> of continuations at call time. Results may become stale
   * immediately.
   *
   * @return list of recoverable continuations (empty if none exist)
   */
  List<RecoverableContinuation> listContinuations();

  /**
   * List current recoverable continuations filtered by disposition.
   *
   * <p>Useful for operational queries like:
   *
   * <ul>
   *   <li>Show all RUNNABLE continuations (can resume without external input)
   *   <li>Show all WAITING_FOR_SIGNAL continuations (require operator approval/decision)
   * </ul>
   *
   * @param disposition the continuation disposition to filter by
   * @return list of matching continuations (empty if none match)
   * @throws NullPointerException if disposition is null
   */
  List<RecoverableContinuation> listContinuationsByDisposition(ContinuationDisposition disposition);

  /**
   * Get a specific recoverable continuation by process ID.
   *
   * <p>Returns {@link Optional#empty()} if:
   *
   * <ul>
   *   <li>Process does not exist
   *   <li>Process completed/failed (checkpoint deleted)
   *   <li>Process is currently executing (no suspended checkpoint)
   * </ul>
   *
   * @param processId the process identifier
   * @return the continuation descriptor if suspended, empty otherwise
   * @throws NullPointerException if processId is null
   */
  Optional<RecoverableContinuation> getContinuation(String processId);

  /**
   * Trigger operator-driven recovery for a suspended continuation.
   *
   * <p>This delegates into M6 durable recovery: {@code
   * DurableExecutionEngine.resumeProcess(processId, signal)}.
   *
   * <h3>Version Safety</h3>
   *
   * <p>The {@code expectedVersion} parameter ensures safe recovery from potentially stale
   * descriptors. If the checkpoint was advanced/deleted since discovery, M6 CHECK A will reject the
   * recovery attempt.
   *
   * <h3>Signal Semantics</h3>
   *
   * <p>The signal is passed through to M6 continuation logic:
   *
   * <ul>
   *   <li><strong>RUNNABLE</strong> continuations: signal may be {@code null} or carry optional
   *       context
   *   <li><strong>WAITING_FOR_SIGNAL</strong> continuations: signal typically carries
   *       approval/rejection/external input required by business logic
   * </ul>
   *
   * <h3>Recovery Outcomes</h3>
   *
   * <ul>
   *   <li><strong>Success</strong> — continuation resumed, execution advanced/completed
   *   <li><strong>Stale Version</strong> — checkpoint version mismatch (another worker resumed it)
   *   <li><strong>Binding Resolution Failure</strong> — runtime dependencies cannot be resolved
   *   <li><strong>Recovery Blocked</strong> — unresolved MAY_HAVE_INVOKED attempt (see M6-T5)
   *   <li><strong>Not Found</strong> — checkpoint does not exist (completed/deleted)
   * </ul>
   *
   * <p>All outcomes propagate as typed exceptions from M6 layer.
   *
   * @param processId the process identifier
   * @param expectedVersion the checkpoint version from the descriptor (CAS token)
   * @param signal the continuation signal (may be null for RUNNABLE; typically required for
   *     WAITING_FOR_SIGNAL)
   * @throws NullPointerException if processId is null
   * @throws cn.bitcss.arctra.checkpoint.StaleCheckpointException if expectedVersion does not match
   *     current checkpoint
   * @throws cn.bitcss.arctra.core.RuntimeBindingResolutionException if runtime binding cannot be
   *     resolved
   * @throws cn.bitcss.arctra.runtime.RecoveryBlockedException if recovery is blocked by unresolved
   *     MAY_HAVE_INVOKED attempt
   * @throws cn.bitcss.arctra.checkpoint.CheckpointNotFoundException if checkpoint does not exist
   */
  void resumeContinuation(String processId, long expectedVersion, ContinuationSignal signal);
}
