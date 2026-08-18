package cn.bitcss.arctra.process;

/**
 * Continuation signal for resuming suspended process.
 *
 * <p>ContinuationSignal represents an external event or decision that allows a suspended
 * AgentProcess to continue execution.
 *
 * <h2>Usage</h2>
 *
 * <pre>{@code
 * // Approval signal
 * ContinuationSignal signal = new ApprovalSignal(true, "Approved by user");
 * AgentResult result = process.resume(signal);
 * }</pre>
 *
 * @author lov3r
 * @since M4
 */
public sealed interface ContinuationSignal permits ContinuationSignal.ApprovalSignal {

  /**
   * Approval signal for governance-driven suspension.
   *
   * <p>Represents human approval decision for high-risk operations.
   *
   * @param approved whether operation was approved
   * @param reason approval or rejection reason
   */
  record ApprovalSignal(boolean approved, String reason) implements ContinuationSignal {
    public ApprovalSignal {
      if (reason == null || reason.isBlank()) {
        throw new IllegalArgumentException("reason cannot be null or blank");
      }
    }
  }
}
