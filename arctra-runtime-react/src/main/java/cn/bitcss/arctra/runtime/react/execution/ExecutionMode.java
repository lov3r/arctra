package cn.bitcss.arctra.runtime.react.execution;

/**
 * Execution mode enum (M6-T6.4 Phase 10: Interface Abstraction).
 *
 * <p>Defines the execution durability semantics for agent processes.
 *
 * <h2>Modes:</h2>
 *
 * <ul>
 *   <li><strong>EPHEMERAL:</strong> In-memory, process-local execution. Suspensions are tied to the
 *       current JVM. No cross-incarnation recovery. Suitable for interactive, short-lived tasks.
 *   <li><strong>DURABLE:</strong> Checkpoint-backed execution. Suspensions are materialized to
 *       persistent storage (CheckpointStore). Supports cross-JVM recovery. Suitable for long-running,
 *       mission-critical tasks.
 * </ul>
 *
 * <h2>Extensibility:</h2>
 *
 * <p>This enum replaces the boolean {@code isDurableMode} flag, enabling future extensions such as:
 *
 * <ul>
 *   <li>HYBRID - Adaptive mode that switches between ephemeral and durable
 *   <li>DISTRIBUTED - Multi-node distributed execution
 *   <li>STREAMING - Stream-based execution for real-time processing
 * </ul>
 *
 * @since M6-T6.4 Phase 10
 */
public enum ExecutionMode {
  /**
   * In-memory, process-local execution.
   *
   * <p>Characteristics:
   *
   * <ul>
   *   <li>Fast (no I/O overhead)
   *   <li>No cross-JVM recovery
   *   <li>Suspensions tied to current process
   *   <li>Suitable for interactive, short-lived tasks
   * </ul>
   */
  EPHEMERAL,

  /**
   * Checkpoint-backed, durable execution.
   *
   * <p>Characteristics:
   *
   * <ul>
   *   <li>Persistent (survives JVM restarts)
   *   <li>Cross-JVM recovery support
   *   <li>Higher latency (checkpoint I/O)
   *   <li>Suitable for long-running, mission-critical tasks
   * </ul>
   */
  DURABLE
}
