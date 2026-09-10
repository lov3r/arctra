package cn.bitcss.arctra.execution;

import java.util.List;

/**
 * Durable execution event ledger.
 *
 * <p>Provides append-only storage of execution events. ExecutionLedger is the durable historical
 * fact authority - records are immutable once written and provide an authoritative audit trail of
 * process execution.
 *
 * <h2>Authority</h2>
 *
 * <p>ExecutionLedger is the <strong>historical fact authority</strong>. It records what happened
 * during execution. It is NOT the current recovery state authority - that remains with {@link
 * cn.bitcss.arctra.checkpoint.SuspensionCheckpoint}.
 *
 * <p><strong>M6-T1 recovery algorithms MUST NOT infer current resumable state from ledger
 * history.</strong> Recovery uses Checkpoint. Ledger provides audit/query/diagnosis capabilities.
 * Arctra is not an event-sourced runtime in M6-T1.
 *
 * <h2>Append-Only Semantics</h2>
 *
 * <p>Ledger is strictly append-only. Historical records cannot be updated or deleted. This ensures
 * audit integrity. The interface provides no update/delete/replace methods.
 *
 * <h2>Sequence Allocation</h2>
 *
 * <p>Ledger atomically allocates monotonic sequence numbers per process. Sequences are:
 *
 * <ul>
 *   <li>Positive (>= 1)
 *   <li>Unique within processId
 *   <li>Strictly increasing according to ledger allocation order
 *   <li><strong>NOT guaranteed gapless</strong> - gaps may occur
 * </ul>
 *
 * <p>Sequence defines ledger historical ordering authority, not wall-clock invocation order or
 * thread scheduling order. See {@link ExecutionRecord#sequence()} for detailed semantics.
 *
 * <h2>Synchronous Visibility</h2>
 *
 * <p>A successful {@link #append} means the ledger accepted the record and returns the canonical
 * stored representation. For the same ledger instance, subsequent queries must be able to retrieve
 * the appended record.
 *
 * <p>This does NOT guarantee:
 *
 * <ul>
 *   <li>fsync / replicated durable commit (storage implementation concern)
 *   <li>cross-instance visibility (distributed implementation concern)
 * </ul>
 *
 * <h2>Concurrency</h2>
 *
 * <p>Multiple concurrent appends to the same process are safe - ledger ensures sequence uniqueness
 * and monotonicity.
 *
 * @author lov3r
 * @since M6-T1
 */
public interface ExecutionLedger {

  /**
   * Append execution record.
   *
   * <p>Ledger atomically allocates sequence number and derives recordId. Returns complete record
   * with assigned identifiers.
   *
   * <p><strong>Sequence allocation:</strong> Sequences are unique and monotonically increasing
   * within processId, but NOT guaranteed gapless. Gaps may occur due to failed appends, transaction
   * rollbacks, distributed allocation, or concurrency.
   *
   * <p><strong>Visibility:</strong> A successful append means the record is accepted and queryable
   * from this ledger instance. Cross-instance visibility depends on storage implementation.
   *
   * @param processId process identifier
   * @param eventType event type
   * @param checkpointVersion checkpoint version when event occurred (null if not checkpointed)
   * @param payload event-specific payload as JSON string (null if no additional data)
   * @return complete record with ledger-assigned sequence and recordId
   * @throws NullPointerException if processId or eventType is null
   * @throws IllegalArgumentException if checkpointVersion is non-null and <= 0
   */
  ExecutionRecord append(
      String processId, EventType eventType, Long checkpointVersion, String payload);

  /**
   * Query records by process.
   *
   * <p>Returns all records for the given process, ordered by sequence (ascending - oldest first).
   *
   * <p><strong>Note:</strong> Sequence numbers may have gaps. Do not assume contiguous numbering.
   *
   * @param processId process identifier
   * @return list of records ordered by sequence ascending, empty if no records
   * @throws NullPointerException if processId is null
   */
  List<ExecutionRecord> queryByProcess(String processId);

  /**
   * Query recent records by process.
   *
   * <p>Returns the most recent N records for the given process, ordered by sequence (descending -
   * newest first).
   *
   * @param processId process identifier
   * @param limit maximum number of records to return
   * @return list of recent records (newest first), empty if no records
   * @throws NullPointerException if processId is null
   * @throws IllegalArgumentException if limit < 1
   */
  List<ExecutionRecord> queryRecentByProcess(String processId, int limit);
}
