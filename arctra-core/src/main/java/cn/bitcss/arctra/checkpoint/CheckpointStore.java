package cn.bitcss.arctra.checkpoint;

import java.util.List;
import java.util.Optional;

/**
 * Durable checkpoint storage for WAITING suspension recovery.
 *
 * <p>Provides atomic conditional operations for checkpoint lifecycle management. Operations are
 * derived from actual lifecycle transitions - this is NOT generic CRUD.
 *
 * <h2>Concurrency Semantics</h2>
 *
 * <ul>
 *   <li>{@link #create}: fails if processId already exists
 *   <li>{@link #load}: always reads current state
 *   <li>{@link #replaceIfVersion}: conditional full replacement (CAS)
 *   <li>{@link #deleteIfVersion}: conditional deletion (CAS)
 *   <li>{@link #listContinuations}: snapshot query (M7)
 * </ul>
 *
 * <h2>Correctness Guarantees</h2>
 *
 * <p>Conditional operations prevent stale executions from corrupting checkpoint state, but do
 * <strong>NOT</strong> prevent duplicate tool execution.
 *
 * <p>M5 provides <strong>conditional checkpoint transitions</strong> with <strong>at-least-once
 * execution</strong>, NOT execution exclusivity or exactly-once guarantees.
 *
 * <h2>M7 Discovery Semantics</h2>
 *
 * <p>Query operations ({@link #listContinuations}, {@link #listContinuationsByDisposition})
 * return snapshot views of current recoverable continuations. Results are NOT leases or ownership
 * claims - concurrent modifications may occur between query and recovery attempt.
 *
 * @author lov3r
 * @since M5
 * @see SuspensionCheckpoint
 */
public interface CheckpointStore {

  /**
   * Create initial suspension checkpoint.
   *
   * <p>Used for initial durable suspension only. MUST fail if processId already exists to prevent
   * accidental overwrite of active checkpoints.
   *
   * @param checkpoint the checkpoint to create
   * @throws CheckpointAlreadyExistsException if processId already exists
   * @throws NullPointerException if checkpoint is null
   */
  void create(SuspensionCheckpoint checkpoint);

  /**
   * Load checkpoint by processId.
   *
   * <p>Returns current checkpoint state if exists.
   *
   * @param processId the process identifier
   * @return checkpoint if exists, empty if not found
   * @throws NullPointerException if processId is null
   */
  Optional<SuspensionCheckpoint> load(String processId);

  /**
   * Replace checkpoint conditionally (re-suspension).
   *
   * <p>Atomic full replacement. Succeeds ONLY IF current checkpointVersion matches
   * expectedVersion. Used when process re-suspends with new pending tools.
   *
   * <p><strong>Post-Execution Operation:</strong> This typically occurs AFTER tool execution and
   * model continuation. If version mismatch, stale execution cannot corrupt newer checkpoint, but
   * side effects may have already occurred (at-least-once semantics).
   *
   * @param processId the process identifier
   * @param expectedVersion the expected current checkpointVersion
   * @param replacement the new checkpoint (with incremented version)
   * @return true if replaced (version matched), false if version mismatch
   * @throws NullPointerException if any parameter is null
   */
  boolean replaceIfVersion(
      String processId, long expectedVersion, SuspensionCheckpoint replacement);

  /**
   * Delete checkpoint conditionally (completion/failure).
   *
   * <p>Atomic conditional deletion. Succeeds ONLY IF current checkpointVersion matches
   * expectedVersion. Used for terminal state (COMPLETED/FAILED) invalidation.
   *
   * <p><strong>Post-Execution Operation:</strong> This typically occurs AFTER execution completes
   * or fails. If version mismatch, stale execution cannot delete newer checkpoint, but side
   * effects may have already occurred (at-least-once semantics).
   *
   * @param processId the process identifier
   * @param expectedVersion the expected current checkpointVersion
   * @return true if deleted (version matched), false if version mismatch
   * @throws NullPointerException if processId is null
   */
  boolean deleteIfVersion(String processId, long expectedVersion);

  // ========== M7: Discovery Operations ==========

  /**
   * List all current recoverable continuations.
   *
   * <p><strong>M7 Recovery Control Plane:</strong> Enables operational discovery of suspended
   * processes across runtime instances.
   *
   * <h2>Snapshot Semantics</h2>
   *
   * <p>Returns a point-in-time snapshot. Checkpoints may be created, advanced, or deleted
   * concurrently with or after this query. Results are NOT leases or ownership claims.
   *
   * <h2>Discovery is NOT Ownership</h2>
   *
   * <p>Multiple runtime instances may discover the same continuation. Listing does NOT grant
   * exclusive execution rights. Concurrency safety remains enforced by M6 CHECK A/B during actual
   * recovery attempts.
   *
   * <h2>Ordering</h2>
   *
   * <p>Implementation-defined ordering. Callers requiring specific order should sort results.
   *
   * @return list of current checkpoints, empty if none exist
   * @since M7
   */
  List<SuspensionCheckpoint> listContinuations();

  /**
   * List continuations by disposition.
   *
   * <p>Filters current recoverable continuations by {@link ContinuationDisposition}. Useful for
   * operational queries distinguishing automatic-runnable processes from approval-waiting ones.
   *
   * <h2>Operational Use Cases</h2>
   *
   * <ul>
   *   <li>{@code RUNNABLE}: Find processes ready for automatic worker recovery
   *   <li>{@code WAITING_FOR_SIGNAL}: Find processes blocked on approval/external input
   * </ul>
   *
   * <h2>Snapshot Semantics</h2>
   *
   * <p>Same snapshot/non-ownership semantics as {@link #listContinuations()}.
   *
   * @param disposition the continuation disposition to filter by
   * @return filtered list of checkpoints, empty if none match
   * @throws NullPointerException if disposition is null
   * @since M7
   */
  List<SuspensionCheckpoint> listContinuationsByDisposition(ContinuationDisposition disposition);
}
