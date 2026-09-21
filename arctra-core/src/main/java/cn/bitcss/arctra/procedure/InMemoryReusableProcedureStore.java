package cn.bitcss.arctra.procedure;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

/**
 * In-memory reusable procedure store.
 *
 * <p>Reference implementation for M8-A testing and single-JVM verification. Uses {@link
 * ConcurrentHashMap} for thread-safe storage.
 *
 * <p><strong>NOT for distributed deployment</strong> - state is JVM-local only. For production
 * shared storage, use JDBC-backed implementation.
 *
 * @author lov3r
 * @since M8-A
 */
public class InMemoryReusableProcedureStore implements ReusableProcedureStore {

  // Key: "procedureId:revision" → ReusableProcedure
  private final ConcurrentMap<String, ReusableProcedure> store = new ConcurrentHashMap<>();

  @Override
  public void createRevision(ReusableProcedure procedure) {
    Objects.requireNonNull(procedure, "procedure cannot be null");

    String key = toKey(procedure.procedureId(), procedure.revision());
    ReusableProcedure existing = store.putIfAbsent(key, procedure);

    if (existing != null) {
      throw new ProcedureAlreadyExistsException(procedure.procedureId(), procedure.revision());
    }
  }

  @Override
  public Optional<ReusableProcedure> findRevision(String procedureId, int revision) {
    Objects.requireNonNull(procedureId, "procedureId cannot be null");

    String key = toKey(procedureId, revision);
    return Optional.ofNullable(store.get(key));
  }

  @Override
  public List<ReusableProcedure> listRevisions(String procedureId) {
    Objects.requireNonNull(procedureId, "procedureId cannot be null");

    return store.values().stream()
        .filter(p -> p.procedureId().equals(procedureId))
        .sorted((a, b) -> Integer.compare(a.revision(), b.revision()))
        .collect(Collectors.toList());
  }

  @Override
  public List<ReusableProcedure> findByIntent(ProcedureScope scope, String intentKey) {
    Objects.requireNonNull(scope, "scope cannot be null");
    Objects.requireNonNull(intentKey, "intentKey cannot be null");

    return store.values().stream()
        .filter(p -> p.scope().equals(scope) && p.intentKey().equals(intentKey))
        .collect(Collectors.toList());
  }

  @Override
  public void updateStatus(String procedureId, int revision, ProcedureStatus newStatus) {
    Objects.requireNonNull(procedureId, "procedureId cannot be null");
    Objects.requireNonNull(newStatus, "newStatus cannot be null");

    String key = toKey(procedureId, revision);

    store.compute(
        key,
        (k, current) -> {
          if (current == null) {
            throw new ProcedureNotFoundException(procedureId, revision);
          }
          return current.withStatus(newStatus);
        });
  }

  private static String toKey(String procedureId, int revision) {
    return procedureId + ":" + revision;
  }

  /**
   * Expose internal store for test verification.
   *
   * <p><strong>NOT PART OF PUBLIC API</strong> - for testing only.
   *
   * @return unmodifiable view of internal procedure store
   */
  protected java.util.Map<String, ReusableProcedure> getProcedures() {
    return java.util.Collections.unmodifiableMap(store);
  }
}
