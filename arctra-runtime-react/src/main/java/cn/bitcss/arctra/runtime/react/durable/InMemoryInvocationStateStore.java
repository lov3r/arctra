package cn.bitcss.arctra.runtime.react.durable;

import cn.bitcss.arctra.recovery.InvalidRecoveryResolutionException;
import cn.bitcss.arctra.recovery.OperationResolution;
import cn.bitcss.arctra.recovery.RecoveryResolutionConflictException;
import cn.bitcss.arctra.recovery.ResolutionType;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * In-memory invocation-state store with thread-safe semantics.
 *
 * <p>Reference implementation for M6-T4A/M6-T5 foundation. Validates recovery-critical
 * invocation-intent authority semantics within one JVM.
 *
 * <p><strong>NOT restart durable:</strong> State is JVM-local only. This implementation does NOT
 * survive JVM restart. For production deployments requiring restart durability, use persistent
 * implementations (JDBC, Redis, etc.).
 *
 * <h2>M6-T5: Physical Attempt Identity & Recovery Resolution</h2>
 *
 * <p>M6-T5 extends this store to support:
 *
 * <ul>
 *   <li>Physical attempt identity (attemptId)
 *   <li>Multiple attempts per logical operation
 *   <li>Recovery resolution (NOT_EXECUTED, EXECUTED with result)
 *   <li>Attempt enumeration
 * </ul>
 *
 * <h2>Storage Model</h2>
 *
 * <pre>
 * (processId, operationId, attemptId) → InvocationIntentRecord
 * (processId, operationId, attemptId) → OperationResolution
 * </pre>
 *
 * <h2>Thread Safety</h2>
 *
 * <p>All methods are thread-safe. Uses {@link ConcurrentHashMap} for concurrent access.
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
 * @since M6-T4A
 * @since M6-T5 Attempt identity, recovery resolution
 */
public final class InMemoryInvocationStateStore implements InvocationStateStore {

  // Composite key for intent/resolution lookup
  private record IntentKey(String processId, String operationId, String attemptId) {}

  // Intent record with timestamp
  private record InvocationIntentRecord(String attemptId, Instant recordedAt) {}

  // Storage: (processId, operationId, attemptId) → intent record
  private final ConcurrentMap<IntentKey, InvocationIntentRecord> intents =
      new ConcurrentHashMap<>();

  // Storage: (processId, operationId, attemptId) → resolution
  private final ConcurrentMap<IntentKey, OperationResolution> resolutions =
      new ConcurrentHashMap<>();

  @Override
  public void recordInvocationIntent(String processId, String operationId, String attemptId) {
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

    // Idempotent: putIfAbsent only stores if absent
    IntentKey key = new IntentKey(processId, operationId, attemptId);
    intents.putIfAbsent(key, new InvocationIntentRecord(attemptId, Instant.now()));
  }

  @Override
  public boolean hasInvocationIntent(String processId, String operationId, String attemptId) {
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
    if (attemptId == null) {
      throw new NullPointerException("attemptId cannot be null");
    }
    if (attemptId.isBlank()) {
      throw new IllegalArgumentException("attemptId cannot be blank");
    }

    IntentKey key = new IntentKey(processId, operationId, attemptId);
    return intents.containsKey(key);
  }

  @Override
  public List<InvocationAttempt> findAttempts(String processId, String operationId) {
    Objects.requireNonNull(processId, "processId cannot be null");
    Objects.requireNonNull(operationId, "operationId cannot be null");

    if (processId.isBlank()) {
      throw new IllegalArgumentException("processId cannot be blank");
    }
    if (operationId.isBlank()) {
      throw new IllegalArgumentException("operationId cannot be blank");
    }

    // Filter intents for this process and operation
    List<InvocationAttempt> attempts = new ArrayList<>();

    for (Map.Entry<IntentKey, InvocationIntentRecord> entry : intents.entrySet()) {
      IntentKey key = entry.getKey();
      if (key.processId().equals(processId) && key.operationId().equals(operationId)) {
        InvocationIntentRecord record = entry.getValue();

        // Look up resolution for this attempt
        Optional<OperationResolution> resolution = Optional.ofNullable(resolutions.get(key));

        attempts.add(new InvocationAttempt(record.attemptId(), record.recordedAt(), resolution));
      }
    }

    // Sort by recorded timestamp (oldest first)
    attempts.sort(Comparator.comparing(InvocationAttempt::recordedAt));

    return attempts;
  }

  @Override
  public void recordResolution(
      String processId,
      String operationId,
      String attemptId,
      ResolutionType type,
      String recoveredResult) {

    Objects.requireNonNull(processId, "processId cannot be null");
    Objects.requireNonNull(operationId, "operationId cannot be null");
    Objects.requireNonNull(attemptId, "attemptId cannot be null");
    Objects.requireNonNull(type, "type cannot be null");

    if (processId.isBlank()) {
      throw new IllegalArgumentException("processId cannot be blank");
    }
    if (operationId.isBlank()) {
      throw new IllegalArgumentException("operationId cannot be blank");
    }
    if (attemptId.isBlank()) {
      throw new IllegalArgumentException("attemptId cannot be blank");
    }

    // Validate recoveredResult based on type
    if (type == ResolutionType.EXECUTED && recoveredResult == null) {
      throw new IllegalArgumentException("recoveredResult required for EXECUTED resolution");
    }
    if (type == ResolutionType.NOT_EXECUTED && recoveredResult != null) {
      throw new IllegalArgumentException(
          "recoveredResult must be null for NOT_EXECUTED resolution");
    }

    IntentKey key = new IntentKey(processId, operationId, attemptId);

    // Check if intent exists
    if (!intents.containsKey(key)) {
      throw new InvalidRecoveryResolutionException(
          "No invocation intent exists for attempt: " + attemptId);
    }

    // Create new resolution
    OperationResolution newResolution =
        type == ResolutionType.NOT_EXECUTED
            ? OperationResolution.notExecuted(operationId, attemptId, Instant.now())
            : OperationResolution.executed(operationId, attemptId, recoveredResult, Instant.now());

    // Atomic check-and-set for idempotency and conflict detection
    OperationResolution existing = resolutions.putIfAbsent(key, newResolution);

    if (existing != null) {
      // Resolution already exists: check semantic equality
      if (existing.semanticallyEquals(newResolution)) {
        // Idempotent: same semantic resolution
        return;
      } else {
        // Conflict: different resolution
        throw new RecoveryResolutionConflictException(
            String.format(
                "Conflicting resolution for attempt %s: existing=%s, new=%s",
                attemptId, existing.type(), type));
      }
    }
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

    IntentKey key = new IntentKey(processId, operationId, attemptId);
    return Optional.ofNullable(resolutions.get(key));
  }

  /**
   * Clear all state (package-private for testing).
   *
   * <p>Not part of InvocationStateStore contract. Used by tests to reset state between test cases.
   */
  void clear() {
    intents.clear();
    resolutions.clear();
  }
}
