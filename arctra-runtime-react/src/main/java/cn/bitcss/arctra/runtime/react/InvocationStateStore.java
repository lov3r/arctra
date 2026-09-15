package cn.bitcss.arctra.runtime.react;

/**
 * Recovery-critical invocation-state authority.
 *
 * <p>Owns the durable pre-call fact that physical invocation for a logical tool operation has been
 * permitted and MAY proceed. This is NOT history (ExecutionLedger), NOT suspension state
 * (Checkpoint), NOT external commit truth (External System).
 *
 * <h2>M6-T4A: Invocation Intent Foundation</h2>
 *
 * <p>The fact {@code INVOCATION_INTENT(processId, operationId)} means:
 *
 * <blockquote>
 * Physical invocation for this logical operation is now permitted and MAY occur.
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
 * <h2>Crash-After-Intent Semantics</h2>
 *
 * <p>Critical invariant: If {@link #recordInvocationIntent} succeeds, then crash occurs before
 * delegate.call() actually enters, recovery MUST still conservatively interpret the operation as
 * MAY_HAVE_INVOKED. Recovery cannot distinguish "intent recorded but never called" from "intent
 * recorded and called".
 *
 * <h2>Hard Gate</h2>
 *
 * <p>Intent recording is a <strong>mandatory pre-call gate</strong>, NOT best-effort observation:
 *
 * <pre>
 * recordInvocationIntent(processId, operationId)
 *   ↓ SUCCESS ONLY
 * delegate.call(...)
 * </pre>
 *
 * <p>If persistence fails, physical invocation MUST NOT proceed.
 *
 * <h2>No Claiming Semantics</h2>
 *
 * <p>Intent recording is <strong>idempotent as a state write</strong>, NOT operation claiming.
 * Multiple concurrent workers may record intent for the same operationId. Both may execute. This
 * preserves at-least-once semantics.
 *
 * <p><strong>Idempotent intent write ≠ Idempotent tool execution.</strong>
 *
 * <h2>M6-T4A Scope</h2>
 *
 * <p>This interface is intentionally minimal. It does NOT include:
 *
 * <ul>
 *   <li>attemptId (deferred to retry correlation)
 *   <li>Recovery query (deferred to T4B)
 *   <li>Outcome persistence (TOOL_EXECUTED/FAILED already exist)
 *   <li>Retry logic
 *   <li>Idempotency keys
 *   <li>External receipts
 *   <li>Recovery policy
 * </ul>
 *
 * <h2>Implementation Requirements</h2>
 *
 * <p>Implementations must be thread-safe. InMemoryInvocationStateStore is JVM-local reference
 * implementation. Persistent implementations (JDBC, Redis) required for production restart
 * durability.
 *
 * <p>Package-private internal authority. Not part of public API.
 *
 * @author lov3r
 * @since M6-T4A
 */
interface InvocationStateStore {

  /**
   * Record durable invocation intent.
   *
   * <p>After this succeeds, physical invocation MAY proceed. If this fails, physical invocation
   * MUST NOT proceed.
   *
   * <p><strong>Idempotency:</strong> Recording the same (processId, operationId) multiple times
   * succeeds. This is monotonic state write, NOT claiming. Multiple workers may record intent and
   * execute.
   *
   * <p><strong>Failure semantics:</strong> Persistence failure MUST block physical invocation. This
   * is framework infrastructure failure, NOT tool failure (TOOL_FAILED is only for delegate
   * exceptions).
   *
   * @param processId stable process identifier (non-null, non-blank)
   * @param operationId logical durable tool operation identity (non-null, non-blank)
   * @throws InvocationIntentPersistenceException if persistence fails
   * @throws NullPointerException if processId or operationId is null
   * @throws IllegalArgumentException if processId or operationId is blank
   */
  void recordInvocationIntent(String processId, String operationId);

  /**
   * Check if invocation intent exists for operation.
   *
   * <p><strong>M6-T4B: Recovery read visibility.</strong> Enables recovery classification:
   *
   * <ul>
   *   <li>{@code true} → Intent exists (gate was crossed, MAY_HAVE_INVOKED)
   *   <li>{@code false} → Intent definitely absent (gate never crossed, DEFINITELY_NOT_DISPATCHED)
   *   <li>{@code throws} → Cannot determine (storage failure)
   * </ul>
   *
   * <p><strong>Critical: Unknown ≠ Absent.</strong> Storage read failure must throw exception, NOT
   * return false. Treating unknown state as "absent" could cause re-execution of already-invoked
   * operations.
   *
   * <p><strong>Input semantics:</strong>
   *
   * <ul>
   *   <li>Unknown process → false (no intent for unknown process, not an error)
   *   <li>Known process, unknown operation → false (no intent for that operation)
   *   <li>Invalid IDs (null/blank) → fail fast with exception
   * </ul>
   *
   * <p><strong>Implementation note:</strong> InMemoryInvocationStateStore cannot normally produce
   * read failures (JVM-local). Future persistent implementations (JDBC, Redis) may throw on
   * storage/network failures.
   *
   * @param processId stable process identifier (non-null, non-blank)
   * @param operationId logical durable tool operation identity (non-null, non-blank)
   * @return true if intent recorded, false if authoritatively absent
   * @throws NullPointerException if processId or operationId is null
   * @throws IllegalArgumentException if processId or operationId is blank
   * @throws RuntimeException if storage read fails (implementation-specific exception type)
   * @since M6-T4B
   */
  boolean hasInvocationIntent(String processId, String operationId);
}
