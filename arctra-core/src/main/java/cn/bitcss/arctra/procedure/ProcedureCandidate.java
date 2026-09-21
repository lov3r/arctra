package cn.bitcss.arctra.procedure;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Extracted procedure candidate awaiting promotion.
 *
 * <p><strong>M8-A V1:</strong> Represents extracted reusable structure that has NOT been verified
 * and promoted. Candidates are NOT automatically executable.
 *
 * <p><strong>Lifecycle:</strong> Candidate extraction (M8-B) → validation → explicit promotion
 * (M8-E) → active ReusableProcedure.
 *
 * <p>Candidate and ReusableProcedure lifecycles are distinct. Existence of a candidate does NOT
 * make it executable as an active procedure.
 *
 * @param candidateId unique candidate identifier
 * @param scope procedure scope
 * @param intentKey proposed intent key
 * @param proposedSteps extracted procedure steps
 * @param derivedFrom execution ID this was extracted from
 * @param createdAt extraction timestamp
 * @param validationOutcome validation result (null if not yet validated)
 * @author lov3r
 * @since M8-A
 */
public record ProcedureCandidate(
    String candidateId,
    ProcedureScope scope,
    String intentKey,
    List<ProcedureStep> proposedSteps,
    String derivedFrom,
    Instant createdAt,
    String validationOutcome) {

  public ProcedureCandidate {
    if (candidateId == null || candidateId.isBlank()) {
      throw new IllegalArgumentException("candidateId cannot be null or blank");
    }
    Objects.requireNonNull(scope, "scope cannot be null");
    if (intentKey == null || intentKey.isBlank()) {
      throw new IllegalArgumentException("intentKey cannot be null or blank");
    }
    Objects.requireNonNull(proposedSteps, "proposedSteps cannot be null");
    if (proposedSteps.isEmpty()) {
      throw new IllegalArgumentException("proposedSteps cannot be empty");
    }
    if (derivedFrom == null || derivedFrom.isBlank()) {
      throw new IllegalArgumentException("derivedFrom cannot be null or blank");
    }
    Objects.requireNonNull(createdAt, "createdAt cannot be null");

    // Defensive immutable copy
    proposedSteps = List.copyOf(proposedSteps);
  }

  /**
   * Update validation outcome.
   *
   * @param outcome validation result
   * @return new instance with validation outcome
   */
  public ProcedureCandidate withValidation(String outcome) {
    return new ProcedureCandidate(
        candidateId, scope, intentKey, proposedSteps, derivedFrom, createdAt, outcome);
  }
}
