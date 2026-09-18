package cn.bitcss.arctra.durability;

/**
 * Execution durability mode.
 *
 * <p>Determines whether accepted semantic execution progress must survive process/JVM failures.
 *
 * <h2>Semantics</h2>
 *
 * <ul>
 *   <li><strong>EPHEMERAL</strong>: Lightweight execution. Semantic progress does NOT survive
 *       crashes. No durable continuation materialization. No checkpoint writes. Fast path.
 *   <li><strong>DURABLE</strong>: Recoverable execution. Accepted semantic progress (logical
 *       operations, continuation state) survives crashes. Requires durable continuation
 *       materialization. Higher persistence cost.
 * </ul>
 *
 * <h2>Orthogonality</h2>
 *
 * <p>Durability is independent from:
 *
 * <ul>
 *   <li>Governance (ALLOW / REQUIRE_APPROVAL / REJECT)
 *   <li>Continuation disposition (RUNNABLE / WAITING / COMPLETED)
 * </ul>
 *
 * <h2>Governance Interaction</h2>
 *
 * <p>REQUIRE_APPROVAL naturally escalates to DURABLE because waiting for external signals cannot
 * safely remain JVM-local.
 *
 * <h2>Not Exactly-Once</h2>
 *
 * <p>DURABLE does NOT guarantee:
 *
 * <ul>
 *   <li>Exactly-once model calls
 *   <li>Exactly-once tool execution
 *   <li>Exactly-once external side effects
 * </ul>
 *
 * <p>DURABLE provides:
 *
 * <ul>
 *   <li>Stable logical operation identity across crashes
 *   <li>At-least-once physical invocation semantics
 *   <li>Operator-driven uncertainty resolution
 *   <li>Cross-JVM boundary recovery
 * </ul>
 *
 * @author lov3r
 * @since M6-T6.4
 */
public enum DurabilityMode {

  /**
   * Ephemeral execution (default).
   *
   * <p>No durable continuation. Lightweight fast path. Semantic progress does NOT survive crashes.
   */
  EPHEMERAL,

  /**
   * Durable execution.
   *
   * <p>Durable continuation materialization. Accepted semantic progress survives crashes.
   * At-least-once execution semantics.
   */
  DURABLE
}
