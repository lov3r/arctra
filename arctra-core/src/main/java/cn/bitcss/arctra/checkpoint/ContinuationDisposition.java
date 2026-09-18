package cn.bitcss.arctra.checkpoint;

/**
 * Continuation disposition for durable process.
 *
 * <p>Represents the semantic state of a durable continuation, determining whether the process can
 * automatically continue, must wait for external signal, or has completed.
 *
 * <h2>M6-T6.4 Self-Describing Continuation</h2>
 *
 * <p>This type makes durable continuation checkpoints self-describing. A persisted checkpoint
 * should be able to answer "what is this process waiting/runnable on?" without inferring semantics
 * from usage context.
 *
 * <h2>Orthogonality</h2>
 *
 * <p>Continuation disposition is independent from:
 *
 * <ul>
 *   <li>Governance (ALLOW / REQUIRE_APPROVAL / REJECT)
 *   <li>Durability (EPHEMERAL / DURABLE)
 * </ul>
 *
 * <h2>Semantics</h2>
 *
 * <ul>
 *   <li><strong>RUNNABLE</strong>: Process has pending operations that can execute automatically.
 *       No external signal required. Typical for ALLOW + DURABLE automatic execution.
 *   <li><strong>WAITING_FOR_SIGNAL</strong>: Process suspended awaiting external signal (e.g.,
 *       approval). Cannot continue automatically. Typical for REQUIRE_APPROVAL suspension.
 * </ul>
 *
 * <p>Terminal states (COMPLETED / FAILED) delete checkpoint rather than persist disposition.
 *
 * @author lov3r
 * @since M6-T6.4
 */
public enum ContinuationDisposition {

  /**
   * Runnable continuation.
   *
   * <p>Process has pending operations that can execute automatically without external signal.
   * Typical for ALLOW + DURABLE execution with pending tool batch.
   */
  RUNNABLE,

  /**
   * Waiting for external signal.
   *
   * <p>Process suspended awaiting external input (e.g., approval decision). Cannot continue
   * automatically. Typical for REQUIRE_APPROVAL governance suspension.
   */
  WAITING_FOR_SIGNAL
}
