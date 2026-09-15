package cn.bitcss.arctra.runtime.react;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * In-memory invocation-state store with thread-safe semantics.
 *
 * <p>Reference implementation for M6-T4A foundation. Validates recovery-critical invocation-intent
 * authority semantics within one JVM.
 *
 * <p><strong>NOT restart durable:</strong> State is JVM-local only. This implementation does NOT
 * survive JVM restart. For production deployments requiring restart durability, use persistent
 * implementations (JDBC, Redis, etc.).
 *
 * <h2>Storage Model</h2>
 *
 * <pre>
 * processId → Set&lt;operationId&gt;
 * </pre>
 *
 * <p>Uses {@link ConcurrentHashMap} with {@link ConcurrentHashMap#newKeySet()} for thread-safe
 * Set storage.
 *
 * <h2>Idempotency</h2>
 *
 * <p>Recording the same (processId, operationId) multiple times succeeds without error. This is
 * monotonic state write (intent exists), NOT claiming. Both workers may proceed to execute.
 *
 * <h2>Thread Safety</h2>
 *
 * <p>All methods are thread-safe. Concurrent recordings for the same operationId are safe and will
 * not corrupt state.
 *
 * <h2>Limitations</h2>
 *
 * <ul>
 *   <li>JVM-local: state lost on restart
 *   <li>Single-node: cannot coordinate across processes
 *   <li>No persistence: no recovery after crash
 *   <li>Test/reference only: NOT for production
 * </ul>
 *
 * <p>Package-private. Not part of public API.
 *
 * @author lov3r
 * @since M6-T4A
 */
class InMemoryInvocationStateStore implements InvocationStateStore {

  // processId → Set<operationId>
  private final ConcurrentMap<String, Set<String>> intents = new ConcurrentHashMap<>();

  /**
   * Record durable invocation intent.
   *
   * <p>In-memory implementation: always succeeds (no actual persistence failure possible). Adds
   * operationId to the process's intent set. Idempotent: recording same intent multiple times
   * succeeds.
   *
   * @param processId stable process identifier
   * @param operationId logical durable tool operation identity
   * @throws NullPointerException if processId or operationId is null
   * @throws IllegalArgumentException if processId or operationId is blank
   */
  @Override
  public void recordInvocationIntent(String processId, String operationId) {
    Objects.requireNonNull(processId, "processId cannot be null");
    Objects.requireNonNull(operationId, "operationId cannot be null");

    if (processId.isBlank()) {
      throw new IllegalArgumentException("processId cannot be blank");
    }
    if (operationId.isBlank()) {
      throw new IllegalArgumentException("operationId cannot be blank");
    }

    // Idempotent: computeIfAbsent ensures thread-safe Set creation
    // add() is idempotent (returns false if already present, but no error)
    intents.computeIfAbsent(processId, k -> ConcurrentHashMap.newKeySet()).add(operationId);
  }

  /**
   * Check if invocation intent exists for operation.
   *
   * <p><strong>M6-T4B: Recovery read authority implementation.</strong>
   *
   * <p>Returns true if intent was recorded, false if definitely absent. This is a JVM-local
   * in-memory lookup and cannot normally fail (no storage/network errors possible).
   *
   * <p><strong>Semantics:</strong>
   *
   * <ul>
   *   <li>Known process + known operation → true
   *   <li>Known process + unknown operation → false
   *   <li>Unknown process → false (no intents for unknown process)
   * </ul>
   *
   * @param processId process identifier
   * @param operationId operation identifier
   * @return true if intent recorded, false if authoritatively absent
   * @throws NullPointerException if processId or operationId is null
   * @throws IllegalArgumentException if processId or operationId is blank
   */
  @Override
  public boolean hasInvocationIntent(String processId, String operationId) {
    // Validate inputs (match recordInvocationIntent validation)
    if (processId == null) {
      throw new NullPointerException("processId cannot be null");
    }
    if (processId.isBlank()) {
      throw new IllegalArgumentException("processId cannot be blank");
    }
    if (operationId == null) {
      throw new NullPointerException("operationId cannot be null");
    }
    if (operationId.isBlank()) {
      throw new IllegalArgumentException("operationId cannot be blank");
    }

    Set<String> processIntents = intents.get(processId);
    return processIntents != null && processIntents.contains(operationId);
  }

  /**
   * Clear all intents (package-private for testing).
   *
   * <p>Not part of InvocationStateStore contract. Used by tests to reset state between test cases.
   */
  void clear() {
    intents.clear();
  }
}
