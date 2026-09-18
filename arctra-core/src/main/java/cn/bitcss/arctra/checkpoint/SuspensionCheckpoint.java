package cn.bitcss.arctra.checkpoint;

import cn.bitcss.arctra.evidence.Evidence;
import java.util.List;
import java.util.Objects;

/**
 * Durable checkpoint of a process continuation point.
 *
 * <p>Represents the minimal durable state required to reconstruct and continue an AgentProcess
 * after crossing runtime/JVM boundaries. Contains only framework-neutral types - no Spring AI
 * objects, no continuation closures, no runtime dependencies.
 *
 * <h2>M6-T6.4 Self-Describing Continuation</h2>
 *
 * <p>This checkpoint may represent different continuation dispositions:
 *
 * <ul>
 *   <li><strong>WAITING_FOR_SIGNAL</strong>: Process suspended awaiting external signal (e.g.,
 *       approval). Cannot continue automatically.
 *   <li><strong>RUNNABLE</strong>: Process with pending operations that can automatically continue
 *       (e.g., ALLOW + DURABLE execution).
 * </ul>
 *
 * <p>The {@link #disposition()} field makes this checkpoint self-describing - recovery logic can
 * determine continuation semantics directly from persisted state.
 *
 * <h2>Durable Semantic Contract</h2>
 *
 * <p>This is a <strong>durable semantic contract</strong> - field changes affect checkpoint schema
 * compatibility. Schema evolution must be managed via {@link #schemaVersion()}.
 *
 * <h2>Checkpoint Semantics</h2>
 *
 * <ul>
 *   <li>Checkpoint existence = durable continuation state (terminal states delete checkpoint)
 *   <li>One processId = one logical task execution (stable across re-suspensions)
 *   <li>checkpointVersion increments on each continuation generation (optimistic locking)
 * </ul>
 *
 * <h2>Schema Evolution</h2>
 *
 * <ul>
 *   <li><strong>1.0</strong>: M5 original (no executionEpoch, no disposition)
 *   <li><strong>1.1</strong>: M6-T4F added executionEpoch (nullable)
 *   <li><strong>1.2</strong>: M6-T6.4 added disposition (nullable for legacy compatibility)
 * </ul>
 *
 * @param schemaVersion checkpoint schema version (for evolution compatibility)
 * @param processId stable process identifier (unique per logical task)
 * @param checkpointVersion checkpoint generation (for optimistic locking)
 * @param runtimeBindingKey application-defined key for RuntimeBindingResolver
 * @param sessionId optional session identifier (null for stateless)
 * @param disposition continuation disposition (RUNNABLE / WAITING_FOR_SIGNAL), nullable for legacy
 * @param pendingBatch pending tool calls awaiting execution/approval (non-empty)
 * @param accumulatedEvidences execution evidence accumulated before suspension (immutable)
 * @param executionEpoch optional execution epoch for crash/restart detection (M6-T4F)
 * @author lov3r
 * @since M5
 * @since M6-T6.4 disposition field added
 */
public record SuspensionCheckpoint(
    String schemaVersion,
    String processId,
    long checkpointVersion,
    String runtimeBindingKey,
    String sessionId,
    ContinuationDisposition disposition,
    List<PendingToolCall> pendingBatch,
    List<Evidence> accumulatedEvidences,
    String executionEpoch) {

  /**
   * Current checkpoint schema version.
   *
   * <p><strong>Schema 1.2</strong>: M6-T6.4 added disposition (self-describing continuation)
   */
  public static final String CURRENT_SCHEMA_VERSION = "1.2";

  /**
   * Compact constructor with validation.
   *
   * @throws IllegalArgumentException if required fields are invalid
   */
  public SuspensionCheckpoint {
    if (schemaVersion == null || schemaVersion.isBlank()) {
      throw new IllegalArgumentException("schemaVersion cannot be null or blank");
    }
    if (processId == null || processId.isBlank()) {
      throw new IllegalArgumentException("processId cannot be null or blank");
    }
    if (checkpointVersion <= 0) {
      throw new IllegalArgumentException(
          "checkpointVersion must be positive (got: " + checkpointVersion + ")");
    }
    if (runtimeBindingKey == null || runtimeBindingKey.isBlank()) {
      throw new IllegalArgumentException("runtimeBindingKey cannot be null or blank");
    }
    // sessionId may be null for stateless execution
    // disposition may be null ONLY for legacy v1.0/v1.1 checkpoint compatibility
    // executionEpoch may be null for v1.0/v1.1 checkpoint compatibility
    if (pendingBatch == null || pendingBatch.isEmpty()) {
      throw new IllegalArgumentException("pendingBatch cannot be null or empty");
    }
    Objects.requireNonNull(accumulatedEvidences, "accumulatedEvidences cannot be null");

    // Defensive immutable copies
    pendingBatch = List.copyOf(pendingBatch);
    accumulatedEvidences = List.copyOf(accumulatedEvidences);
  }
}
