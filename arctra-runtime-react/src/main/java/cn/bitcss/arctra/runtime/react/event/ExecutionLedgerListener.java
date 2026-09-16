package cn.bitcss.arctra.runtime.react.event;

import cn.bitcss.arctra.execution.ExecutionEvent;
import cn.bitcss.arctra.execution.ExecutionEventListener;
import cn.bitcss.arctra.execution.ExecutionLedger;
import java.util.Objects;

/**
 * Execution ledger projection listener.
 *
 * <p>Projects execution events to durable audit ledger. This is an adapter that bridges {@link
 * ExecutionEvent} (ephemeral notification) to {@link ExecutionLedger} (durable historical
 * storage).
 *
 * <h2>Authority</h2>
 *
 * <p>ExecutionLedgerListener is a <strong>projection adapter only</strong>. It does NOT:
 *
 * <ul>
 *   <li>Act as recovery authority (Checkpoint remains recovery authority)
 *   <li>Allocate sequence numbers (ExecutionLedger allocates sequence)
 *   <li>Derive recordId (ExecutionLedger derives recordId from sequence)
 *   <li>Assign occurredAt timestamp (ExecutionLedger assigns timestamp)
 *   <li>Determine resumability (Checkpoint determines resumability)
 *   <li>Validate CHECK A/B (Checkpoint validates CHECK A/B)
 * </ul>
 *
 * <h2>Failure Semantics</h2>
 *
 * <p>If ledger.append() throws, the exception propagates to the caller (typically {@link
 * CompositeExecutionEventListener} for isolation). Ledger append failure does NOT invalidate the
 * already-true domain fact - it means the audit trail has a gap.
 *
 * @author lov3r
 * @since M6-T2C
 */
public final class ExecutionLedgerListener implements ExecutionEventListener {

  private final ExecutionLedger ledger;

  /**
   * Create ledger projection listener.
   *
   * @param ledger the execution ledger (non-null)
   */
  public ExecutionLedgerListener(ExecutionLedger ledger) {
    this.ledger = Objects.requireNonNull(ledger, "ledger cannot be null");
  }

  @Override
  public void onEvent(ExecutionEvent event) {
    // Direct projection to ledger
    // Ledger allocates sequence, recordId, occurredAt
    // If append throws, exception propagates (caller handles isolation)
    ledger.append(
        event.processId(), event.eventType(), event.checkpointVersion(), event.payload());
  }
}
