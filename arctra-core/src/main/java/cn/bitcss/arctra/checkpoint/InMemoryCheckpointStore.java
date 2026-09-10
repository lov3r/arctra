package cn.bitcss.arctra.checkpoint;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * In-memory checkpoint store with atomic CAS semantics.
 *
 * <p>Reference implementation for M5 testing and single-JVM durable recovery verification. Uses
 * {@link ConcurrentHashMap} compute operations for atomic conditional transitions.
 *
 * <p><strong>NOT for distributed deployment</strong> - state is JVM-local only. For production
 * shared storage, use JDBC or Redis-backed implementations.
 *
 * @author lov3r
 * @since M5
 */
public class InMemoryCheckpointStore implements CheckpointStore {

  private final ConcurrentMap<String, SuspensionCheckpoint> store = new ConcurrentHashMap<>();

  @Override
  public void create(SuspensionCheckpoint checkpoint) {
    Objects.requireNonNull(checkpoint, "checkpoint cannot be null");

    String processId = checkpoint.processId();
    SuspensionCheckpoint existing = store.putIfAbsent(processId, checkpoint);

    if (existing != null) {
      throw new CheckpointAlreadyExistsException(processId);
    }
  }

  @Override
  public Optional<SuspensionCheckpoint> load(String processId) {
    Objects.requireNonNull(processId, "processId cannot be null");
    return Optional.ofNullable(store.get(processId));
  }

  @Override
  public boolean replaceIfVersion(
      String processId, long expectedVersion, SuspensionCheckpoint replacement) {
    Objects.requireNonNull(processId, "processId cannot be null");
    Objects.requireNonNull(replacement, "replacement cannot be null");

    // Invariant: replacement processId must match key
    if (!replacement.processId().equals(processId)) {
      throw new IllegalArgumentException(
          "Replacement checkpoint processId ("
              + replacement.processId()
              + ") does not match key ("
              + processId
              + ")");
    }

    // Track whether conditional mutation succeeded
    boolean[] replaced = {false};

    store.compute(
        processId,
        (key, current) -> {
          if (current == null) {
            // Checkpoint already deleted/missing - cannot replace
            replaced[0] = false;
            return null;
          }
          if (current.checkpointVersion() == expectedVersion) {
            // Version matches - replace
            replaced[0] = true;
            return replacement;
          }
          // Version mismatch - keep current
          replaced[0] = false;
          return current;
        });

    return replaced[0];
  }

  @Override
  public boolean deleteIfVersion(String processId, long expectedVersion) {
    Objects.requireNonNull(processId, "processId cannot be null");

    // Atomic compute: only delete if current version matches
    boolean[] deleted = {false};
    store.compute(
        processId,
        (key, current) -> {
          if (current == null) {
            // Already deleted/missing
            deleted[0] = false;
            return null;
          }
          if (current.checkpointVersion() == expectedVersion) {
            // Version matches - delete (return null)
            deleted[0] = true;
            return null;
          }
          // Version mismatch - keep current
          deleted[0] = false;
          return current;
        });

    return deleted[0];
  }
}
