package cn.bitcss.arctra.execution;

/**
 * Execution event type.
 *
 * <p>Represents significant events in process execution lifecycle.
 *
 * <h2>M6-T1 Initial Vocabulary</h2>
 *
 * <p>This is a <strong>provisional initial vocabulary</strong> derived from M5 lifecycle. Event
 * semantics will be refined as durable execution capabilities expand. New event types will be added
 * additively as concrete execution scenarios require precise durable semantics.
 *
 * <p><strong>Event names describe facts that have become true</strong>, not intentions or potential
 * outcomes. Semantic precision will increase with operationId, external receipts, and
 * uncertain-outcome handling.
 *
 * <h2>Taxonomy Status</h2>
 *
 * <p>This enumeration is <strong>NOT frozen</strong>. It is an additive vocabulary that will evolve
 * as generalized durable execution capabilities are implemented.
 *
 * @author lov3r
 * @since M6-T1
 */
public enum EventType {

  // Process lifecycle

  /**
   * Process materialized and started execution.
   *
   * <p><strong>Provisional semantics:</strong> Process identity established and execution began.
   * Semantics will be refined when process creation durability is implemented.
   */
  PROCESS_STARTED,

  // Governance (M4/M5 governance semantics)

  /**
   * Governance policy requires approval before tool execution.
   *
   * <p>Associated with checkpoint creation pending human decision.
   */
  APPROVAL_REQUIRED,

  /**
   * Human granted approval for pending tool execution.
   *
   * <p>Signals continuation with APPROVE signal.
   */
  APPROVAL_GRANTED,

  /**
   * Human rejected pending tool execution.
   *
   * <p>Signals continuation with REJECT signal.
   */
  APPROVAL_REJECTED,

  // Suspension/Resume (M5 durable semantics)

  /**
   * Process suspended durably (checkpoint created).
   *
   * <p><strong>Precise semantics:</strong> SuspensionCheckpoint with specific checkpointVersion
   * exists. This represents a durable suspension point.
   */
  SUSPENDED,

  /**
   * Process resumed from checkpoint.
   *
   * <p><strong>Precise semantics:</strong> Checkpoint loaded and CHECK A validation passed.
   */
  RESUMED,

  // Tool execution (provisional semantics pending operationId/receipt refinement)

  /**
   * Framework-observed tool callback execution returned successfully.
   *
   * <p><strong>Provisional semantics:</strong> The tool callback invoked by the framework returned
   * without throwing an exception. This does NOT guarantee:
   *
   * <ul>
   *   <li>External side effect is transactionally committed
   *   <li>External system executed exactly once
   *   <li>Operation receipt has been verified
   * </ul>
   *
   * <p>Semantics will be refined with operationId, external receipts, and uncertain-outcome
   * handling.
   */
  TOOL_EXECUTED,

  /**
   * Framework-observed tool callback execution failed.
   *
   * <p><strong>Provisional semantics:</strong> The tool callback invoked by the framework threw an
   * exception or returned an error. Semantics will be refined with failure classification and retry
   * boundaries.
   */
  TOOL_FAILED,

  // Checkpoint conflicts (M5 precise semantics)

  /**
   * Concurrent modification detected during CHECK B.
   *
   * <p><strong>Precise semantics:</strong> replaceIfVersion or deleteIfVersion returned false due
   * to checkpointVersion mismatch. Indicates concurrent execution detected.
   */
  CHECKPOINT_CONFLICT,

  // Terminal states (provisional semantics)

  /**
   * Process completed (terminal state).
   *
   * <p><strong>Provisional semantics:</strong> Execution finished without suspension. Checkpoint
   * deleted (if existed). Semantics will be refined with completion criteria.
   */
  COMPLETED,

  /**
   * Process failed (terminal state).
   *
   * <p><strong>Provisional semantics:</strong> Execution failed terminally (not retryable).
   * Semantics will be refined with failure classification and retry boundaries.
   */
  FAILED
}
