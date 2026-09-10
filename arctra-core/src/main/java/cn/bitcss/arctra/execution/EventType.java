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
   * <p><strong>Domain commit point:</strong> An APPROVED continuation signal has been validated
   * against the current checkpoint episode, after CHECK A passed and RuntimeBinding successfully
   * resolved. At this point, the approval decision is semantically confirmed, independent of
   * whether the ExecutionRecord is successfully persisted.
   *
   * <p><strong>Conditions for domain fact to become TRUE:</strong>
   * <ol>
   *   <li>CHECK A: checkpoint version validated as current
   *   <li>RuntimeBinding: execution binding successfully reconstructed
   *   <li>ContinuationSignal: APPROVED signal validated/accepted for current checkpoint
   * </ol>
   *
   * <p><strong>Ledger projection:</strong> After the approval fact becomes true, the framework
   * attempts to persist an ExecutionRecord. If ledger append fails, the approval decision remains
   * semantically true (the human did approve), but the audit trail has a gap.
   *
   * <p><strong>Failure scenarios:</strong>
   * <ul>
   *   <li>CHECK A fails → approval fact never becomes true, no ledger append
   *   <li>RuntimeBinding fails → approval fact never becomes true, no ledger append
   *   <li>Ledger append fails → approval fact is TRUE, audit trail incomplete
   * </ul>
   *
   * <p><strong>Audit-critical:</strong> If ledger append fails, subsequent execution behavior
   * depends on policy. The domain fact remains true regardless.
   */
  APPROVAL_GRANTED,

  /**
   * Human rejected pending tool execution.
   *
   * <p><strong>Domain commit point:</strong> A REJECTED continuation signal has been validated
   * against the current checkpoint episode, after CHECK A passed and RuntimeBinding successfully
   * resolved. At this point, the rejection decision is semantically confirmed, independent of
   * whether the ExecutionRecord is successfully persisted.
   *
   * <p><strong>Conditions for domain fact to become TRUE:</strong>
   * <ol>
   *   <li>CHECK A: checkpoint version validated as current
   *   <li>RuntimeBinding: execution binding successfully reconstructed
   *   <li>ContinuationSignal: REJECTED signal validated/accepted for current checkpoint
   * </ol>
   *
   * <p><strong>Ledger projection:</strong> After the rejection fact becomes true, the framework
   * attempts to persist an ExecutionRecord. If ledger append fails, the rejection decision remains
   * semantically true, but the audit trail has a gap.
   *
   * <p><strong>Failure scenarios:</strong>
   * <ul>
   *   <li>CHECK A fails → rejection fact never becomes true, no ledger append
   *   <li>RuntimeBinding fails → rejection fact never becomes true, no ledger append
   *   <li>Ledger append fails → rejection fact is TRUE, audit trail incomplete
   * </ul>
   *
   * <p><strong>Audit-critical:</strong> Append failure handling is symmetric with APPROVAL_GRANTED.
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
   * <p><strong>Domain commit point:</strong> The durable continuation environment has been
   * successfully prepared from the current checkpoint. CHECK A passed, RuntimeBinding resolved,
   * approval decision confirmed (approved or rejected), and control is ready to enter the resumed
   * execution path. At this point, resume has semantically occurred, independent of whether the
   * ExecutionRecord is successfully persisted.
   *
   * <p><strong>Conditions for domain fact to become TRUE:</strong>
   * <ol>
   *   <li>CHECK A: checkpoint version validated as current
   *   <li>RuntimeBinding: execution binding successfully reconstructed
   *   <li>Approval decision: APPROVAL_GRANTED or APPROVAL_REJECTED semantic decision confirmed
   *       (not "recorded" — the decision exists as a domain fact)
   *   <li>Continuation environment prepared and ready to enter resumed execution
   * </ol>
   *
   * <p><strong>Ledger projection:</strong> After the resume fact becomes true, the framework
   * attempts to persist an ExecutionRecord. If ledger append fails, the resume operation remains
   * semantically true (runtime continuation did begin), but the audit trail has a gap.
   *
   * <p><strong>Failure scenarios:</strong>
   * <ul>
   *   <li>CHECK A fails → resume fact never becomes true, no ledger append
   *   <li>RuntimeBinding fails → resume fact never becomes true, no ledger append
   *   <li>Ledger append fails → resume fact is TRUE, audit trail incomplete
   * </ul>
   *
   * <p><strong>Distinction from APPROVAL_GRANTED/REJECTED:</strong> Approval events represent
   * the governance decision. RESUMED represents the technical runtime continuation beginning.
   * RESUMED depends on the approval decision existing (as a domain fact), not on the approval
   * decision being successfully recorded in the ledger.
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
