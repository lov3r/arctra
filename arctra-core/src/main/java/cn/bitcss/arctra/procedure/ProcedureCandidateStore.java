package cn.bitcss.arctra.procedure;

import java.util.List;
import java.util.Optional;

/**
 * Storage for unpromoted procedure candidates.
 *
 * <p><strong>M8-A V1:</strong> Stores extracted procedure candidates awaiting validation and
 * promotion. Candidates are NOT executable procedures.
 *
 * <p><strong>Authority Separation:</strong>
 *
 * <ul>
 *   <li>This store owns unpromoted candidate facts only
 *   <li>ReusableProcedureStore owns active procedure definitions
 *   <li>These are separate authorities with distinct lifecycles
 * </ul>
 *
 * @author lov3r
 * @since M8-A
 */
public interface ProcedureCandidateStore {

  /**
   * Save candidate.
   *
   * @param candidate candidate to save
   * @throws IllegalArgumentException if candidate with same candidateId already exists
   * @throws NullPointerException if candidate is null
   */
  void save(ProcedureCandidate candidate);

  /**
   * Find candidate by ID.
   *
   * @param candidateId candidate identifier
   * @return candidate if exists
   * @throws NullPointerException if candidateId is null
   */
  Optional<ProcedureCandidate> findById(String candidateId);

  /**
   * List candidates by scope.
   *
   * <p>Returns all candidates for given scope, regardless of validation status.
   *
   * @param scope procedure scope
   * @return matching candidates (may be empty)
   * @throws NullPointerException if scope is null
   */
  List<ProcedureCandidate> listByScope(ProcedureScope scope);

  /**
   * Update candidate validation outcome.
   *
   * @param candidateId candidate identifier
   * @param validationOutcome validation result
   * @throws IllegalArgumentException if candidate not found
   * @throws NullPointerException if candidateId or validationOutcome is null
   */
  void updateValidation(String candidateId, String validationOutcome);

  /**
   * Delete candidate.
   *
   * <p>Used after successful promotion or explicit rejection.
   *
   * @param candidateId candidate identifier
   * @return true if deleted, false if not found
   * @throws NullPointerException if candidateId is null
   */
  boolean delete(String candidateId);
}
