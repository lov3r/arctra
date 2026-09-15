package cn.bitcss.arctra.execution;

import java.util.Objects;

/**
 * Execution event representing an already-true runtime/domain fact.
 *
 * <p>ExecutionEvent is a <strong>notification</strong> that a domain fact has become true. It is
 * NOT:
 *
 * <ul>
 *   <li>A durable historical record (that is {@link ExecutionRecord})
 *   <li>A recovery state authority (that is {@link cn.bitcss.arctra.checkpoint.SuspensionCheckpoint})
 *   <li>A command or intention
 *   <li>A creator of domain truth
 * </ul>
 *
 * <h2>Domain Fact Precedes Event</h2>
 *
 * <p>The correct flow is:
 *
 * <pre>
 * Domain transition succeeds
 *     ↓
 * Domain fact becomes TRUE
 *     ↓
 * ExecutionEvent emitted
 *     ↓
 * Listeners project/observe the fact
 * </pre>
 *
 * <p>Listeners MUST NOT create domain truth. They project already-true facts into audit trails,
 * metrics, logs, or other observation systems.
 *
 * <h2>ExecutionEvent vs ExecutionRecord</h2>
 *
 * <p>ExecutionEvent is ephemeral notification at the moment a fact becomes true. It has no:
 *
 * <ul>
 *   <li>{@code sequence} - assigned by {@link ExecutionLedger} during append
 *   <li>{@code recordId} - derived by Ledger as {@code processId:sequence}
 *   <li>{@code occurredAt} - assigned by Ledger at storage time
 * </ul>
 *
 * <p>Those identifiers remain {@link ExecutionLedger} and {@link ExecutionRecord} concerns.
 * ExecutionEvent only carries the domain fact notification fields.
 *
 * <h2>Authority</h2>
 *
 * <p>ExecutionEvent does NOT determine:
 *
 * <ul>
 *   <li>Whether a process is resumable (Checkpoint authority)
 *   <li>Current checkpoint version (Checkpoint authority)
 *   <li>CHECK A validity (Checkpoint authority)
 *   <li>CHECK B success (Checkpoint authority)
 *   <li>Historical event ordering (ExecutionLedger sequence authority)
 * </ul>
 *
 * @param processId stable process identifier (non-null, non-blank)
 * @param eventType type of event (non-null)
 * @param checkpointVersion checkpoint version when event occurred (nullable - not all events occur
 *     during checkpointed states)
 * @param payload event-specific details as JSON string (nullable)
 * @author lov3r
 * @since M6-T2C
 */
public record ExecutionEvent(
    String processId, EventType eventType, Long checkpointVersion, String payload) {

  public ExecutionEvent {
    // processId: non-null and non-blank
    Objects.requireNonNull(processId, "processId cannot be null");
    if (processId.isBlank()) {
      throw new IllegalArgumentException("processId cannot be blank");
    }

    // eventType: non-null
    Objects.requireNonNull(eventType, "eventType cannot be null");

    // checkpointVersion: nullable, but if non-null must be positive
    if (checkpointVersion != null && checkpointVersion <= 0) {
      throw new IllegalArgumentException(
          "checkpointVersion must be positive when non-null (got: " + checkpointVersion + ")");
    }

    // payload: nullable and opaque - no validation
  }
}
