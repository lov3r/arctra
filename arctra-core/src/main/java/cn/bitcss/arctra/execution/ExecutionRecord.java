package cn.bitcss.arctra.execution;

import java.time.Instant;
import java.util.Objects;

/**
 * Durable execution event record.
 *
 * <p>Represents a significant event in process execution lifecycle. ExecutionRecord is the durable
 * execution history authority - records are append-only and immutable once written.
 *
 * <h2>Authority</h2>
 *
 * <p>ExecutionRecord provides the authoritative audit trail of <strong>what happened during
 * execution</strong>. It is NOT a checkpoint (recovery state authority), log (observability), or
 * trace span (timing).
 *
 * <p><strong>ExecutionLedger is historical fact authority.</strong> Recovery state authority
 * remains with {@link cn.bitcss.arctra.checkpoint.SuspensionCheckpoint}. M6-T1 recovery algorithms
 * MUST NOT infer current resumable state from ledger history replay. Checkpoint remains recovery
 * authority; ledger provides audit/query/diagnosis.
 *
 * <h2>Record Identity</h2>
 *
 * <p>Canonical record identity is the composite key {@code (processId, sequence)}. The {@code
 * recordId} field provides a derived string representation for external references, logging, and
 * debugging. Format: {@code processId + ":" + sequence}.
 *
 * <h2>Sequence Semantics</h2>
 *
 * <p>The {@code sequence} field defines ledger ordering authority within a process. Sequence
 * numbers are:
 *
 * <ul>
 *   <li>Positive (>= 1)
 *   <li>Unique within processId
 *   <li>Strictly monotonically increasing according to ledger allocation order
 *   <li>Ledger-assigned (not caller-assigned)
 * </ul>
 *
 * <p><strong>Sequences are NOT guaranteed gapless.</strong> Gaps may occur due to failed appends,
 * transaction rollbacks, distributed allocation, or concurrency. Do not assume sequence N+1 exists
 * after sequence N.
 *
 * <p>Sequence provides <strong>ledger ordering authority</strong>, not wall-clock invocation order.
 * If two records have different timestamps but defined sequence order, sequence is authoritative.
 *
 * <h2>Timestamp vs Sequence</h2>
 *
 * <ul>
 *   <li>{@code occurredAt} - observed event time (may be out-of-order due to clock skew)
 *   <li>{@code sequence} - ledger historical ordering authority (total order within process)
 * </ul>
 *
 * <p>If timestamps conflict with sequence order, sequence wins for historical ordering.
 *
 * @param recordId derived identifier (processId:sequence) for external references
 * @param processId stable process identifier
 * @param sequence ledger-assigned monotonic sequence within process
 * @param eventType type of event
 * @param occurredAt event timestamp (UTC)
 * @param checkpointVersion checkpoint version when event occurred (nullable)
 * @param payload event-specific details as JSON string (nullable)
 * @author lov3r
 * @since M6-T1
 */
public record ExecutionRecord(
    String recordId,
    String processId,
    long sequence,
    EventType eventType,
    Instant occurredAt,
    Long checkpointVersion,
    String payload) {

  public ExecutionRecord {
    if (recordId == null || recordId.isBlank()) {
      throw new IllegalArgumentException("recordId cannot be null or blank");
    }
    if (processId == null || processId.isBlank()) {
      throw new IllegalArgumentException("processId cannot be null or blank");
    }
    if (sequence < 1) {
      throw new IllegalArgumentException("sequence must be positive (got: " + sequence + ")");
    }
    Objects.requireNonNull(eventType, "eventType cannot be null");
    Objects.requireNonNull(occurredAt, "occurredAt cannot be null");

    // Enforce recordId consistency: recordId must equal processId:sequence
    String expectedRecordId = processId + ":" + sequence;
    if (!recordId.equals(expectedRecordId)) {
      throw new IllegalArgumentException(
          "recordId must equal processId:sequence (expected: "
              + expectedRecordId
              + ", got: "
              + recordId
              + ")");
    }

    // Validate checkpointVersion when non-null
    if (checkpointVersion != null && checkpointVersion <= 0) {
      throw new IllegalArgumentException(
          "checkpointVersion must be positive when non-null (got: " + checkpointVersion + ")");
    }

    // checkpointVersion nullable - not all events occur during checkpointed states
    // payload nullable - some events have no additional data
  }
}
