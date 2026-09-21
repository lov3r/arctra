package cn.bitcss.arctra.procedure;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Immutable reusable procedure revision.
 *
 * <p><strong>M8-A V1 Reusable Execution Knowledge Authority:</strong> Represents verified,
 * reusable, executable procedure structure. Does NOT contain runtime execution state or identities.
 *
 * <p><strong>Identity:</strong> {@code (procedureId, revision)} is immutable and unique. Updating a
 * procedure creates a new revision with same procedureId and incremented revision number.
 *
 * <p><strong>V1 Constraints:</strong>
 *
 * <ul>
 *   <li>Linear procedures only (ordered steps, no branches)
 *   <li>Deterministic parameter bindings
 *   <li>No dynamic model reasoning inside procedure
 *   <li>Read-only/idempotent tools only (enforced by candidate validation)
 * </ul>
 *
 * <p><strong>Does NOT contain:</strong>
 *
 * <ul>
 *   <li>Runtime identities (processId, operationId, checkpointVersion)
 *   <li>Current execution position (currentStepIndex - owned by CheckpointStore)
 *   <li>Captured step outputs (owned by checkpoint continuation state)
 *   <li>Physical attempt information (owned by InvocationStateStore)
 * </ul>
 *
 * @param procedureId stable logical procedure identifier
 * @param revision immutable revision number (1, 2, 3, ...)
 * @param scope procedure scope (agent-based in V1)
 * @param intentKey deterministic routing key for intent matching
 * @param steps ordered linear procedure steps
 * @param status lifecycle status (mutable metadata, not part of executable definition)
 * @param createdAt creation timestamp
 * @param derivedFrom provenance - execution ID this was learned from (null if manually defined)
 * @author lov3r
 * @since M8-A
 */
public record ReusableProcedure(
    String procedureId,
    int revision,
    ProcedureScope scope,
    String intentKey,
    List<ProcedureStep> steps,
    ProcedureStatus status,
    Instant createdAt,
    String derivedFrom) {

  public ReusableProcedure {
    if (procedureId == null || procedureId.isBlank()) {
      throw new IllegalArgumentException("procedureId cannot be null or blank");
    }
    if (revision <= 0) {
      throw new IllegalArgumentException("revision must be positive: " + revision);
    }
    Objects.requireNonNull(scope, "scope cannot be null");
    if (intentKey == null || intentKey.isBlank()) {
      throw new IllegalArgumentException("intentKey cannot be null or blank");
    }
    Objects.requireNonNull(steps, "steps cannot be null");
    if (steps.isEmpty()) {
      throw new IllegalArgumentException("steps cannot be empty");
    }
    Objects.requireNonNull(status, "status cannot be null");
    Objects.requireNonNull(createdAt, "createdAt cannot be null");

    // Defensive immutable copy
    steps = List.copyOf(steps);

    // Validate step indexes are contiguous and start from 0
    for (int i = 0; i < steps.size(); i++) {
      if (steps.get(i).stepIndex() != i) {
        throw new IllegalArgumentException(
            "Step indexes must be contiguous starting from 0. Expected "
                + i
                + " but got "
                + steps.get(i).stepIndex());
      }
    }

    // Validate output references across steps
    for (int i = 0; i < steps.size(); i++) {
      ProcedureStep step = steps.get(i);
      List<ProcedureStep> previousSteps = steps.subList(0, i);
      step.validateOutputReferences(previousSteps);
    }
  }

  /**
   * Create next revision of this procedure.
   *
   * <p>Creates new revision with same procedureId and incremented revision number. Current revision
   * remains immutable.
   *
   * @param newSteps updated procedure steps
   * @param derivedFrom provenance for new revision
   * @return new revision
   */
  public ReusableProcedure createNextRevision(List<ProcedureStep> newSteps, String derivedFrom) {
    return new ReusableProcedure(
        procedureId,
        revision + 1,
        scope,
        intentKey,
        newSteps,
        ProcedureStatus.VALID,
        Instant.now(),
        derivedFrom);
  }

  /**
   * Update status (mutable metadata).
   *
   * <p>Status changes do NOT mutate executable definition. Returns new instance with updated
   * status.
   *
   * @param newStatus new lifecycle status
   * @return new instance with updated status
   */
  public ReusableProcedure withStatus(ProcedureStatus newStatus) {
    Objects.requireNonNull(newStatus, "newStatus cannot be null");
    return new ReusableProcedure(
        procedureId, revision, scope, intentKey, steps, newStatus, createdAt, derivedFrom);
  }
}
