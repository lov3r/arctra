package cn.bitcss.arctra.runtime.react;

import cn.bitcss.arctra.checkpoint.CheckpointStore;
import cn.bitcss.arctra.checkpoint.ContinuationDisposition;
import cn.bitcss.arctra.checkpoint.SuspensionCheckpoint;
import cn.bitcss.arctra.controlplane.RecoverableContinuation;
import cn.bitcss.arctra.controlplane.RecoveryControlPlane;
import cn.bitcss.arctra.process.ContinuationSignal;
import cn.bitcss.arctra.runtime.DurableExecutionEngine;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Default Recovery Control Plane implementation delegating to M6 Durable Execution Kernel.
 *
 * <h2>M7: Recovery Control Plane</h2>
 *
 * <p>This implementation provides operational discovery and operator-driven recovery over M6
 * checkpoint authority and durable execution semantics.
 *
 * <h2>Authority Delegation</h2>
 *
 * <ul>
 *   <li><strong>Discovery</strong> → reads from {@link CheckpointStore} (continuation authority)
 *   <li><strong>Recovery</strong> → delegates to {@link DurableExecutionEngine} (M6 CHECK A/CHECK
 *       B)
 * </ul>
 *
 * <p>This class does NOT duplicate process state, execution logic, or recovery classification. It
 * is a thin operations facade over existing M6 authorities.
 *
 * <h2>Cross-Instance Discovery</h2>
 *
 * <p>When {@code CheckpointStore} is JDBC-backed, multiple JVM instances can discover the same
 * continuations. Concurrent recovery attempts are safely resolved by M6 CAS semantics (checkpoint
 * version validation in CHECK A).
 *
 * <h2>Thread Safety</h2>
 *
 * <p>Thread-safe assuming {@code CheckpointStore} and {@code DurableExecutionEngine} are
 * thread-safe.
 *
 * @author lov3r
 * @since M7
 */
public final class DefaultRecoveryControlPlane implements RecoveryControlPlane {

  private final CheckpointStore checkpointStore;
  private final DurableExecutionEngine durableEngine;

  /**
   * Create default recovery control plane.
   *
   * @param checkpointStore the checkpoint store (continuation authority)
   * @param durableEngine the durable execution engine (M6 recovery delegation target)
   * @throws NullPointerException if any parameter is null
   */
  public DefaultRecoveryControlPlane(
      CheckpointStore checkpointStore, DurableExecutionEngine durableEngine) {
    this.checkpointStore = Objects.requireNonNull(checkpointStore, "checkpointStore cannot be null");
    this.durableEngine = Objects.requireNonNull(durableEngine, "durableEngine cannot be null");
  }

  @Override
  public List<RecoverableContinuation> listContinuations() {
    return checkpointStore.listContinuations().stream()
        .map(this::toRecoverableContinuation)
        .toList();
  }

  @Override
  public List<RecoverableContinuation> listContinuationsByDisposition(
      ContinuationDisposition disposition) {
    Objects.requireNonNull(disposition, "disposition cannot be null");

    return checkpointStore.listContinuationsByDisposition(disposition).stream()
        .map(this::toRecoverableContinuation)
        .toList();
  }

  @Override
  public Optional<RecoverableContinuation> getContinuation(String processId) {
    Objects.requireNonNull(processId, "processId cannot be null");

    return checkpointStore.load(processId).map(this::toRecoverableContinuation);
  }

  @Override
  public void resumeContinuation(String processId, long expectedVersion, ContinuationSignal signal) {
    Objects.requireNonNull(processId, "processId cannot be null");
    // signal may be null

    // Delegate directly to M6 durable recovery
    // M6 CHECK A will validate:
    // - checkpoint exists
    // - checkpoint version matches expectedVersion (CAS)
    // - runtime binding can be resolved
    // - no unresolved MAY_HAVE_INVOKED attempts block recovery
    //
    // M6 execution will:
    // - reconstruct continuation
    // - execute agent logic
    // - commit CHECK B transition
    //
    // All M6 exceptions propagate to caller (typed recovery outcomes)
    durableEngine.resumeProcess(processId, expectedVersion, signal);
  }

  /**
   * Convert SuspensionCheckpoint to RecoverableContinuation descriptor.
   *
   * @param checkpoint the suspension checkpoint
   * @return the recoverable continuation descriptor
   */
  private RecoverableContinuation toRecoverableContinuation(SuspensionCheckpoint checkpoint) {
    return new RecoverableContinuation(
        checkpoint.processId(),
        checkpoint.checkpointVersion(),
        checkpoint.disposition(),
        checkpoint.runtimeBindingKey(),
        checkpoint.sessionId());
  }
}
