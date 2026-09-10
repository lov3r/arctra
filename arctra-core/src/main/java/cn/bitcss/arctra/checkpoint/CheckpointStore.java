package cn.bitcss.arctra.checkpoint;

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
}
