package cn.bitcss.arctra.procedure;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

/**
 * In-memory procedure candidate store.
 *
 * <p>Reference implementation for M8-A testing. Thread-safe using {@link ConcurrentHashMap}.
 *
 * @author lov3r
 * @since M8-A
 */
public class InMemoryProcedureCandidateStore implements ProcedureCandidateStore {

  private final ConcurrentMap<String, ProcedureCandidate> store = new ConcurrentHashMap<>();

  @Override
  public void save(ProcedureCandidate candidate) {
    Objects.requireNonNull(candidate, "candidate cannot be null");

    ProcedureCandidate existing = store.putIfAbsent(candidate.candidateId(), candidate);
    if (existing != null) {
      throw new IllegalArgumentException(
          "Candidate already exists: " + candidate.candidateId());
    }
  }

  @Override
  public Optional<ProcedureCandidate> findById(String candidateId) {
    Objects.requireNonNull(candidateId, "candidateId cannot be null");
    return Optional.ofNullable(store.get(candidateId));
  }

  @Override
  public List<ProcedureCandidate> listByScope(ProcedureScope scope) {
    Objects.requireNonNull(scope, "scope cannot be null");

    return store.values().stream()
        .filter(c -> c.scope().equals(scope))
        .collect(Collectors.toList());
  }

  @Override
  public void updateValidation(String candidateId, String validationOutcome) {
    Objects.requireNonNull(candidateId, "candidateId cannot be null");
    Objects.requireNonNull(validationOutcome, "validationOutcome cannot be null");

    store.compute(
        candidateId,
        (k, current) -> {
          if (current == null) {
            throw new IllegalArgumentException("Candidate not found: " + candidateId);
          }
          return current.withValidation(validationOutcome);
        });
  }

  @Override
  public boolean delete(String candidateId) {
    Objects.requireNonNull(candidateId, "candidateId cannot be null");
    return store.remove(candidateId) != null;
  }

  /**
   * Expose internal store for test verification.
   *
   * <p><strong>NOT PART OF PUBLIC API</strong> - for testing only.
   *
   * @return unmodifiable view of internal candidate store
   */
  protected java.util.Map<String, ProcedureCandidate> getCandidates() {
    return java.util.Collections.unmodifiableMap(store);
  }
}
