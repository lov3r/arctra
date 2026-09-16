package cn.bitcss.arctra.runtime.react.durable;

import cn.bitcss.arctra.checkpoint.CheckpointNotFoundException;
import cn.bitcss.arctra.checkpoint.CheckpointStore;
import cn.bitcss.arctra.checkpoint.PendingToolCall;
import cn.bitcss.arctra.checkpoint.SuspensionCheckpoint;
import cn.bitcss.arctra.execution.EventType;
import cn.bitcss.arctra.execution.ExecutionEvent;
import cn.bitcss.arctra.execution.ExecutionEventListener;
import cn.bitcss.arctra.recovery.InvalidRecoveryResolutionException;
import cn.bitcss.arctra.recovery.OperationResolution;
import cn.bitcss.arctra.recovery.RecoveryResolutionConflictException;
import cn.bitcss.arctra.recovery.ResolutionType;
import cn.bitcss.arctra.recovery.StaleRecoveryResolutionException;
import cn.bitcss.arctra.runtime.RecoveryResolution;
import java.util.Objects;
import java.util.Optional;

/**
 * Default implementation of recovery resolution capability.
 *
 * <p><strong>M6-T5: Durable Recovery Resolution.</strong>
 *
 * <p>Validates resolution requests against current checkpoint and delegates to {@link
 * InvocationStateStore} for durable persistence.
 *
 * <h2>Validation Flow</h2>
 *
 * <pre>
 * 1. Load current checkpoint
 * 2. Validate checkpointVersion matches
 * 3. Validate operationId in pending batch
 * 4. Validate attemptId exists
 * 5. Delegate to InvocationStateStore for resolution persistence
 * 6. Emit RECOVERY_RESOLVED event
 * </pre>
 *
 * <h2>Event Semantics</h2>
 *
 * <p>RECOVERY_RESOLVED event is emitted AFTER durable resolution commit. Event projection failure
 * does not reverse the committed resolution.
 *
 * <p>Package-private. Not part of public API.
 *
 * @since M6-T5
 */
public final class DefaultRecoveryResolution implements RecoveryResolution {

  private final CheckpointStore checkpointStore;
  private final InvocationStateStore invocationStateStore;
  private final ExecutionEventListener eventListener;

  /**
   * Create default recovery resolution.
   *
   * @param checkpointStore checkpoint authority
   * @param invocationStateStore invocation state authority
   * @param eventListener event listener
   */
  DefaultRecoveryResolution(
      CheckpointStore checkpointStore,
      InvocationStateStore invocationStateStore,
      ExecutionEventListener eventListener) {
    this.checkpointStore =
        Objects.requireNonNull(checkpointStore, "checkpointStore cannot be null");
    this.invocationStateStore =
        Objects.requireNonNull(invocationStateStore, "invocationStateStore cannot be null");
    this.eventListener = Objects.requireNonNull(eventListener, "eventListener cannot be null");
  }

  @Override
  public void resolveAsNotExecuted(
      String processId, long checkpointVersion, String operationId, String attemptId) {

    Objects.requireNonNull(processId, "processId cannot be null");
    Objects.requireNonNull(operationId, "operationId cannot be null");
    Objects.requireNonNull(attemptId, "attemptId cannot be null");

    if (processId.isBlank()) {
      throw new IllegalArgumentException("processId cannot be blank");
    }
    if (operationId.isBlank()) {
      throw new IllegalArgumentException("operationId cannot be blank");
    }
    if (attemptId.isBlank()) {
      throw new IllegalArgumentException("attemptId cannot be blank");
    }

    // Validate against current checkpoint
    validateResolution(processId, checkpointVersion, operationId, attemptId);

    // Record resolution
    invocationStateStore.recordResolution(
        processId, operationId, attemptId, ResolutionType.NOT_EXECUTED, null);

    // Emit event (after durable commit)
    emitResolutionEvent(processId, checkpointVersion, operationId, attemptId, "NOT_EXECUTED");
  }

  @Override
  public void resolveAsExecuted(
      String processId,
      long checkpointVersion,
      String operationId,
      String attemptId,
      String recoveredResult) {

    Objects.requireNonNull(processId, "processId cannot be null");
    Objects.requireNonNull(operationId, "operationId cannot be null");
    Objects.requireNonNull(attemptId, "attemptId cannot be null");
    Objects.requireNonNull(recoveredResult, "recoveredResult cannot be null");

    if (processId.isBlank()) {
      throw new IllegalArgumentException("processId cannot be blank");
    }
    if (operationId.isBlank()) {
      throw new IllegalArgumentException("operationId cannot be blank");
    }
    if (attemptId.isBlank()) {
      throw new IllegalArgumentException("attemptId cannot be blank");
    }

    // Validate against current checkpoint
    validateResolution(processId, checkpointVersion, operationId, attemptId);

    // Record resolution
    invocationStateStore.recordResolution(
        processId, operationId, attemptId, ResolutionType.EXECUTED, recoveredResult);

    // Emit event (after durable commit)
    emitResolutionEvent(processId, checkpointVersion, operationId, attemptId, "EXECUTED");
  }

  @Override
  public Optional<OperationResolution> getResolution(
      String processId, String operationId, String attemptId) {

    Objects.requireNonNull(processId, "processId cannot be null");
    Objects.requireNonNull(operationId, "operationId cannot be null");
    Objects.requireNonNull(attemptId, "attemptId cannot be null");

    if (processId.isBlank()) {
      throw new IllegalArgumentException("processId cannot be blank");
    }
    if (operationId.isBlank()) {
      throw new IllegalArgumentException("operationId cannot be blank");
    }
    if (attemptId.isBlank()) {
      throw new IllegalArgumentException("attemptId cannot be blank");
    }

    return invocationStateStore.getResolution(processId, operationId, attemptId);
  }

  /**
   * Validate resolution request against current checkpoint.
   *
   * @throws CheckpointNotFoundException if checkpoint does not exist
   * @throws StaleRecoveryResolutionException if checkpointVersion mismatch
   * @throws InvalidRecoveryResolutionException if operation/attempt invalid
   */
  private void validateResolution(
      String processId, long checkpointVersion, String operationId, String attemptId) {

    // 1. Load current checkpoint
    SuspensionCheckpoint checkpoint =
        checkpointStore
            .load(processId)
            .orElseThrow(
                () ->
                    new CheckpointNotFoundException(
                        "Checkpoint not found for process: " + processId));

    // 2. Validate checkpointVersion matches (CAS-style stale protection)
    if (checkpoint.checkpointVersion() != checkpointVersion) {
      throw new StaleRecoveryResolutionException(
          String.format(
              "Checkpoint version mismatch for process %s: expected=%d, current=%d",
              processId, checkpointVersion, checkpoint.checkpointVersion()));
    }

    // 3. Validate operationId belongs to current pending batch
    boolean operationExists =
        checkpoint.pendingBatch().stream()
            .anyMatch(op -> op.operationId().equals(operationId));

    if (!operationExists) {
      throw new InvalidRecoveryResolutionException(
          String.format(
              "Operation %s not found in current checkpoint pending batch", operationId));
    }

    // 4. Validate attemptId exists (has invocation intent)
    if (!invocationStateStore.hasInvocationIntent(processId, operationId, attemptId)) {
      throw new InvalidRecoveryResolutionException(
          String.format("No invocation intent exists for attempt %s", attemptId));
    }
  }

  /**
   * Emit RECOVERY_RESOLVED event.
   *
   * <p>Event projection failure does not reverse committed resolution.
   */
  private void emitResolutionEvent(
      String processId,
      long checkpointVersion,
      String operationId,
      String attemptId,
      String resolutionType) {

    try {
      String payload =
          String.format(
              "{\"operationId\":\"%s\",\"attemptId\":\"%s\",\"resolutionType\":\"%s\"}",
              operationId, attemptId, resolutionType);

      ExecutionEvent event =
          new ExecutionEvent(
              processId, EventType.RECOVERY_RESOLVED, checkpointVersion, payload);

      eventListener.onEvent(event);
    } catch (Exception e) {
      // Event projection failure does not undo resolution
      // Log warning but do not propagate
      // Production would log: "Failed to emit RECOVERY_RESOLVED event"
    }
  }
}
