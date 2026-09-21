package cn.bitcss.arctra.controlplane;

import cn.bitcss.arctra.checkpoint.ContinuationDisposition;
import java.util.Objects;

/**
 * Operational view of a current recoverable agent continuation.
 *
 * <p>Represents a <strong>snapshot</strong> of a suspended process at discovery time. This is NOT
 * process truth, NOT a lease, and NOT an ownership claim. It is read-only operational metadata
 * derived from the authoritative {@link cn.bitcss.arctra.checkpoint.CheckpointStore}.
 *
 * <h2>M7: Recovery Control Plane</h2>
 *
 * <p>M7 Control Plane exposes <em>discovery</em> and <em>operator-triggered recovery</em> over M6
 * Durable Execution Kernel. The Control Plane is an <strong>operations view</strong>, not execution
 * authority.
 *
 * <h2>Snapshot Semantics</h2>
 *
 * <p>A {@code RecoverableContinuation} descriptor is valid at discovery time but may become stale:
 *
 * <ul>
 *   <li>Another worker may resume/advance/complete the process
 *   <li>Checkpoint version may change
 *   <li>Continuation may be deleted
 * </ul>
 *
 * <p><strong>Stale descriptors are safe</strong>: M6 CHECK A rejects stale checkpoint versions via
 * CAS semantics. Operator recovery using a stale descriptor will fail gracefully.
 *
 * <h2>NOT Ownership</h2>
 *
 * <p>Discovery != Ownership. Listing != Claim. Multiple control plane clients may discover the same
 * continuation. Concurrent recovery attempts are resolved by M6 durable execution semantics (CHECK
 * A/CHECK B), not by control plane locks.
 *
 * <h2>Immutability</h2>
 *
 * <p>Immutable value object. Thread-safe.
 *
 * @param processId the unique process identifier
 * @param checkpointVersion the checkpoint generation (CAS token for recovery)
 * @param disposition continuation disposition (RUNNABLE vs WAITING_FOR_SIGNAL)
 * @param runtimeBindingKey the runtime binding key for dependency resolution
 * @param sessionId the conversation session identifier (may be null)
 * @author lov3r
 * @since M7
 */
public record RecoverableContinuation(
    String processId,
    long checkpointVersion,
    ContinuationDisposition disposition,
    String runtimeBindingKey,
    String sessionId) {

  public RecoverableContinuation {
    Objects.requireNonNull(processId, "processId cannot be null");
    Objects.requireNonNull(disposition, "disposition cannot be null");
    Objects.requireNonNull(runtimeBindingKey, "runtimeBindingKey cannot be null");
    // sessionId may be null
  }

  /**
   * Check if this continuation is runnable without external signal.
   *
   * @return true if disposition is RUNNABLE
   */
  public boolean isRunnable() {
    return disposition == ContinuationDisposition.RUNNABLE;
  }

  /**
   * Check if this continuation is waiting for external signal/approval.
   *
   * @return true if disposition is WAITING_FOR_SIGNAL
   */
  public boolean isWaitingForSignal() {
    return disposition == ContinuationDisposition.WAITING_FOR_SIGNAL;
  }
}
