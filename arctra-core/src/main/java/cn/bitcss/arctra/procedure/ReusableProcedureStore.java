package cn.bitcss.arctra.procedure;

import java.util.List;
import java.util.Optional;

/**
 * Authority for reusable procedure definitions.
 *
 * <p><strong>M8-A V1 Authority Semantics:</strong>
 *
 * <ul>
 *   <li>Owns immutable procedure revision definitions
 *   <li>Does NOT own current execution position (CheckpointStore authority)
 *   <li>Does NOT own physical invocation attempts (InvocationStateStore authority)
 *   <li>Does NOT own runtime bindings or execution state
 * </ul>
 *
 * <p><strong>Immutable Revision Contract:</strong>
 *
 * <p>Once created, a revision's executable definition (steps, bindings, fingerprints) NEVER
 * changes. Status metadata (VALID/INVALID/SUPERSEDED) may change, but executable structure is
 * frozen.
 *
 * <p>Updating a procedure creates a new revision with same procedureId and incremented revision
 * number.
 *
 * <p><strong>No "Active Revision" Authority:</strong>
 *
 * <p>M8-A does NOT define which revision is "active" for routing. That decision is deferred to M8-E
 * intent registration/resolution. This store provides immutable definition storage only.
 *
 * @author lov3r
 * @since M8-A
 */
public interface ReusableProcedureStore {

  /**
   * Create new procedure revision.
   *
   * <p>Stores immutable revision. Identity (procedureId, revision) must be unique.
   *
   * @param procedure procedure revision to create
   * @throws ProcedureAlreadyExistsException if (procedureId, revision) already exists
   * @throws NullPointerException if procedure is null
   */
  void createRevision(ReusableProcedure procedure);

  /**
   * Find specific procedure revision.
   *
   * @param procedureId procedure identifier
   * @param revision revision number
   * @return procedure revision if exists
   * @throws NullPointerException if procedureId is null
   */
  Optional<ReusableProcedure> findRevision(String procedureId, int revision);

  /**
   * List all revisions for a procedure.
   *
   * <p>Returns all revisions ordered by revision number (oldest first).
   *
   * @param procedureId procedure identifier
   * @return all revisions (may be empty)
   * @throws NullPointerException if procedureId is null
   */
  List<ReusableProcedure> listRevisions(String procedureId);

  /**
   * Find procedures by scope and intent key.
   *
   * <p>Returns all procedures (all revisions) matching scope and intent. Caller is responsible for
   * selecting appropriate revision (e.g., highest VALID revision).
   *
   * <p>Used by future intent resolution (M8-E) to find candidate procedures for a given request.
   *
   * @param scope procedure scope
   * @param intentKey intent routing key
   * @return matching procedures (may be empty, may contain multiple revisions)
   * @throws NullPointerException if scope or intentKey is null
   */
  List<ReusableProcedure> findByIntent(ProcedureScope scope, String intentKey);

  /**
   * Update procedure status.
   *
   * <p>Updates lifecycle status (VALID/INVALID/SUPERSEDED). Does NOT mutate executable definition.
   *
   * @param procedureId procedure identifier
   * @param revision revision number
   * @param newStatus new lifecycle status
   * @throws ProcedureNotFoundException if revision not found
   * @throws NullPointerException if procedureId or newStatus is null
   */
  void updateStatus(String procedureId, int revision, ProcedureStatus newStatus);
}
