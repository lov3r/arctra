package cn.bitcss.arctra.checkpoint;

import cn.bitcss.arctra.evidence.Evidence;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Durable checkpoint of a SAFE WAITING suspension point.
 *
 * <p>Represents the minimal durable state required to reconstruct and resume an AgentProcess after
 * complete runtime/JVM boundary. Contains only framework-neutral types - no Spring AI objects, no
 * continuation closures, no runtime dependencies.
 *
 * <h2>Durable Semantic Contract</h2>
 *
 * <p>This is a <strong>durable semantic contract</strong> - field changes affect checkpoint schema
 * compatibility. Schema evolution must be managed via {@link #schemaVersion()}.
 *
 * <h2>Checkpoint Semantics</h2>
 *
 * <ul>
 *   <li>Checkpoint existence = WAITING state (terminal states delete checkpoint)
 *   <li>One processId = one logical task execution (stable across re-suspensions)
 *   <li>checkpointVersion = one suspension episode within that process
 *   <li>Process ≠ Session (checkpoint references sessionId, does not contain conversation history)
 * </ul>
 *
 * <h2>M6-T4F Schema Evolution</h2>
 *
 * <p>Schema v1.1 adds {@link #executionEpoch()} for automatic restart detection and recovery mode
 * selection. This field is nullable for backward compatibility with v1.0 checkpoints.
 *
 * @param schemaVersion checkpoint format version (e.g., "1.0", "1.1")
 * @param processId stable process identity
 * @param checkpointVersion suspension episode version (1, 2, 3...)
 * @param runtimeBindingKey application-defined runtime resolution key
 * @param sessionId session identifier for ChatMemory restoration
 * @param pendingBatch pending tool calls awaiting approval/execution
 * @param accumulatedEvidences evidences collected before suspension
 * @param executionEpoch execution incarnation that committed this checkpoint (M6-T4F, nullable for
 *     v1.0)
 * @author lov3r
 * @since M5
 */
public record SuspensionCheckpoint(
    String schemaVersion,
    String processId,
    long checkpointVersion,
    String runtimeBindingKey,
    String sessionId,
    List<PendingToolCall> pendingBatch,
    List<Evidence> accumulatedEvidences,
    String executionEpoch) {

  /**
   * Current checkpoint schema version.
   *
   * <p>Version history:
   *
   * <ul>
   *   <li>"1.0" — Initial schema with operationId (M6-T3A)
   *   <li>"1.1" — Added executionEpoch for restart detection (M6-T4F)
   * </ul>
   */
  public static final String CURRENT_SCHEMA_VERSION = "1.1";

  public SuspensionCheckpoint {
    if (schemaVersion == null || schemaVersion.isBlank()) {
      throw new IllegalArgumentException("schemaVersion cannot be null or blank");
    }
    if (processId == null || processId.isBlank()) {
      throw new IllegalArgumentException("processId cannot be null or blank");
    }
    if (checkpointVersion <= 0) {
      throw new IllegalArgumentException("checkpointVersion must be positive (got: " + checkpointVersion + ")");
    }
    if (runtimeBindingKey == null || runtimeBindingKey.isBlank()) {
      throw new IllegalArgumentException("runtimeBindingKey cannot be null or blank");
    }
    // sessionId may be null for stateless execution - consistent with AgentExecutionContext
    // executionEpoch may be null for v1.0 checkpoint compatibility
    if (pendingBatch == null || pendingBatch.isEmpty()) {
      throw new IllegalArgumentException("pendingBatch cannot be null or empty");
    }
    Objects.requireNonNull(accumulatedEvidences, "accumulatedEvidences cannot be null");

    // Defensive immutable copies
    pendingBatch = List.copyOf(pendingBatch);
    accumulatedEvidences = List.copyOf(accumulatedEvidences);
  }
}
