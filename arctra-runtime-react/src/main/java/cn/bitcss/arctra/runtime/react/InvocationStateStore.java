package cn.bitcss.arctra.runtime.react;

import cn.bitcss.arctra.recovery.OperationResolution;
import cn.bitcss.arctra.recovery.ResolutionType;
import java.util.List;
import java.util.Optional;

/**
 * Recovery-critical invocation-state authority.
 *
 * <p>Owns the durable pre-call fact that physical invocation for a logical tool operation has been
 * permitted and MAY proceed. This is NOT history (ExecutionLedger), NOT suspension state
 * (Checkpoint), NOT external commit truth (External System).
 *
 * <h2>M6-T4A: Invocation Intent Foundation</h2>
 *
 * <p>The fact {@code INVOCATION_INTENT(processId, operationId, attemptId)} means:
 *
 * <blockquote>
 * Physical invocation for this specific attempt of this logical operation is now permitted and MAY
 * occur.
 * </blockquote>
 *
 * <p><strong>It does NOT mean:</strong>
 *
 * <ul>
 *   <li>Delegate definitely entered
 *   <li>Request definitely reached external system
 *   <li>External side effect committed
 *   <li>Tool succeeded
 *   <li>Tool failed
 *   <li>Operation is claimed
 *   <li>Operation has exactly one executor
 * </ul>
 *
 * <h2>M6-T5: Physical Attempt Identity</h2>
 *
 * <p>M6-T5 adds {@code attemptId} to distinguish multiple physical invocation attempts for the same
 * logical operation. Every possible {@code delegate.call()} receives a unique attemptId.
 *
 * <pre>
 * operationId = stable logical operation identity
 * attemptId = unique physical invocation attempt identity (UUID)
 * </pre>
 *
 * <h2>M6-T5: Recovery Resolution Authority</h2>
 *
 * <p>M6-T5 extends this store to also own recovery resolution facts: operator/reconciliation
 * decisions about uncertain physical attempts.
 *
 * <p>The single conceptual authority now owns:
 *
 * <ul>
 *   <li>Invocation intent (MAY invoke)
 *   <li>Recovery resolution (NOT_EXECUTED or EXECUTED with result)
 * </ul>
 *
 * <p>Physical storage may use multiple tables. That does NOT create multiple semantic authorities.
 *
 * <h2>Crash-After-Intent Semantics</h2>
 *
 * <p>Critical for recovery classification:
 *
 * <pre>
 * Attempt #1:
 *   recordInvocationIntent(proc, op, attempt-1) → commit
 *   crash before delegate.call()
 *
 * Restart:
 *   hasInvocationIntent(proc, op, attempt-1) → true
 *   → MAY_HAVE_INVOKED
 *
 * Operator resolves: NOT_EXECUTED
 *
 * Attempt #2:
 *   recordInvocationIntent(proc, op, attempt-2) → NEW attempt
 *   delegate.call()
 * </pre>
 *
 * @since M6-T4A
 * @since M6-T5 Attempt identity, recovery resolution
 */
interface InvocationStateStore {

  /**
   * Record invocation intent for a physical attempt.
   *
   * <p><strong>M6-T4A: Mandatory hard gate before physical invocation.</strong>
   *
   * <p>M6-T5: Intent is now attempt-specific. Each physical invocation gets a unique attemptId.
   *
   * <p>This method MUST be called and MUST succeed before {@code delegate.call()} for a durable
   * tool operation. If this method throws, physical invocation MUST NOT proceed.
   *
   * <h3>Crash Window Safety</h3>
   *
   * <pre>
   * recordInvocationIntent(...) called
   *   ↓
   * storage commit uncertain
   *   ↓
   * delegate.call() MUST NOT execute yet
   *   ↓
   * only after successful return
   *   ↓
   * delegate.call() MAY execute
   * </pre>
   *
   * <h3>Idempotency</h3>
   *
   * <p>Recording the same (processId, operationId, attemptId) multiple times succeeds. This is
   * monotonic state write (intent exists), NOT claiming.
   *
   * <p>Multiple workers may record intent for the SAME logical operation with DIFFERENT attemptIds.
   * At-least-once semantics preserved.
   *
   * @param processId stable process identifier (non-null, non-blank)
   * @param operationId logical durable tool operation identity (non-null, non-blank)
   * @param attemptId unique physical attempt identity (non-null, non-blank)
   * @throws InvocationIntentPersistenceException if persistence fails
   * @throws NullPointerException if processId, operationId, or attemptId is null
   * @throws IllegalArgumentException if processId, operationId, or attemptId is blank
   * @since M6-T4A
   * @since M6-T5 Attempt identity parameter
   */
  void recordInvocationIntent(String processId, String operationId, String attemptId);

  /**
   * Check if invocation intent exists for a specific physical attempt.
   *
   * <p><strong>M6-T4B: Recovery read visibility.</strong>
   *
   * <p>M6-T5: Now attempt-specific. Returns true only if intent exists for this exact attempt.
   *
   * <ul>
   *   <li>{@code true} → Intent exists for this attempt (gate was crossed, MAY_HAVE_INVOKED)
   *   <li>{@code false} → Intent definitely absent for this attempt
   *   <li>{@code throws} → Cannot determine (storage failure)
   * </ul>
   *
   * <p><strong>Critical: Unknown ≠ Absent.</strong> Storage read failure must throw exception, NOT
   * return false.
   *
   * @param processId stable process identifier (non-null, non-blank)
   * @param operationId logical durable tool operation identity (non-null, non-blank)
   * @param attemptId physical attempt identity (non-null, non-blank)
   * @return true if intent recorded, false if authoritatively absent
   * @throws NullPointerException if processId, operationId, or attemptId is null
   * @throws IllegalArgumentException if processId, operationId, or attemptId is blank
   * @throws RuntimeException if storage read fails (implementation-specific exception type)
   * @since M6-T4B
   * @since M6-T5 Attempt identity parameter
   */
  boolean hasInvocationIntent(String processId, String operationId, String attemptId);

  /**
   * Find all physical attempts for a logical operation.
   *
   * <p><strong>M6-T5.1: Attempt Enumeration for Recovery Aggregation.</strong>
   *
   * <p>Returns all durable physical attempts belonging to the specified logical operation. Used by
   * recovery classification to aggregate multiple concurrent attempts.
   *
   * <p>Result includes:
   *
   * <ul>
   *   <li>Attempt identity and timestamp
   *   <li>Optional recovery resolution (if resolved)
   * </ul>
   *
   * <p>This is an internal method for {@link InvocationRecoveryClassifier}. Do NOT expose publicly.
   *
   * @param processId stable process identifier (non-null, non-blank)
   * @param operationId logical operation identity (non-null, non-blank)
   * @return list of all attempts (may be empty if no attempts exist)
   * @throws NullPointerException if processId or operationId is null
   * @throws IllegalArgumentException if processId or operationId is blank
   * @throws RuntimeException if storage read fails
   * @since M6-T5.1
   */
  List<InvocationAttempt> findAttempts(String processId, String operationId);

  /**
   * Record recovery resolution for a physical attempt.
   *
   * <p><strong>M6-T5: Durable Recovery Resolution.</strong>
   *
   * <p>Records operator/reconciliation decision about an uncertain physical attempt. Once resolved,
   * the framework can safely continue execution:
   *
   * <ul>
   *   <li>NOT_EXECUTED → create new attempt and execute
   *   <li>EXECUTED → skip delegate, use recovered result
   * </ul>
   *
   * <h3>Idempotency and Conflict Detection</h3>
   *
   * <p>Recording the same semantic resolution twice (same type, same result for EXECUTED) succeeds
   * idempotently.
   *
   * <p>Recording a different resolution for the same attempt throws {@link
   * RecoveryResolutionConflictException}.
   *
   * <h3>Validation</h3>
   *
   * <p>Implementation should validate that invocation intent exists for this attempt before
   * recording resolution.
   *
   * @param processId stable process identifier (non-null, non-blank)
   * @param operationId logical operation identity (non-null, non-blank)
   * @param attemptId physical attempt identity (non-null, non-blank)
   * @param type resolution type (non-null)
   * @param recoveredResult recovered result (required for EXECUTED, must be null for NOT_EXECUTED)
   * @throws RecoveryResolutionConflictException if conflicting resolution exists
   * @throws InvalidRecoveryResolutionException if attempt or intent does not exist
   * @throws NullPointerException if required parameters are null
   * @throws IllegalArgumentException if parameters are blank or invalid
   * @throws RuntimeException if persistence fails
   * @since M6-T5
   */
  void recordResolution(
      String processId,
      String operationId,
      String attemptId,
      ResolutionType type,
      String recoveredResult);

  /**
   * Get recovery resolution for a physical attempt.
   *
   * <p><strong>M6-T5: Resolution Query.</strong>
   *
   * <p>Returns the recorded recovery resolution for a specific physical attempt, if one exists.
   *
   * @param processId stable process identifier (non-null, non-blank)
   * @param operationId logical operation identity (non-null, non-blank)
   * @param attemptId physical attempt identity (non-null, non-blank)
   * @return resolution if recorded, empty if no resolution exists
   * @throws NullPointerException if any parameter is null
   * @throws IllegalArgumentException if any parameter is blank
   * @throws RuntimeException if storage read fails
   * @since M6-T5
   */
  Optional<OperationResolution> getResolution(
      String processId, String operationId, String attemptId);
}
