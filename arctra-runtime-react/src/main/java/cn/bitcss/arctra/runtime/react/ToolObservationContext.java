package cn.bitcss.arctra.runtime.react;

import cn.bitcss.arctra.execution.ExecutionEventListener;
import java.util.Objects;

/**
 * Tool observation context for durable resumed execution.
 *
 * <p>Encapsulates correlation information for TOOL_EXECUTED / TOOL_FAILED event emission during
 * checkpoint-backed approved resume executions.
 *
 * <p><strong>M6-T3B: Per-Operation Cardinality:</strong> Contains operationId for exact tool
 * operation correlation. One ToolObservationContext instance per physical tool invocation.
 *
 * <p><strong>Responsibility:</strong> Correlation context for tool execution observation.
 *
 * <p><strong>Does NOT own:</strong>
 *
 * <ul>
 *   <li>attemptId (not yet implemented)
 *   <li>retry metadata (not yet implemented)
 *   <li>Evidence instances (separate authority)
 *   <li>Spring AI Message types (protocol layer concern)
 *   <li>Checkpoint reference (authority boundary separation)
 * </ul>
 *
 * <p>Package-private internal type. Not part of public API.
 *
 * @param processId stable process identifier from checkpoint (required, non-blank)
 * @param checkpointVersion current checkpoint version (required, positive)
 * @param operationId Arctra logical durable tool operation identity (required, non-blank, M6-T3A)
 * @param eventListener event sink for tool observation (required, non-null)
 * @author lov3r
 * @since M6-T2.5A-R2 (operationId added in M6-T3B)
 */
record ToolObservationContext(
    String processId, long checkpointVersion, String operationId, ExecutionEventListener eventListener) {

  /**
   * Compact constructor with validation.
   *
   * <p>Ensures all correlation fields are valid. Null or invalid values are rejected at
   * construction rather than at usage.
   */
  ToolObservationContext {
    Objects.requireNonNull(processId, "processId cannot be null");
    Objects.requireNonNull(operationId, "operationId cannot be null");
    Objects.requireNonNull(eventListener, "eventListener cannot be null");

    if (processId.isBlank()) {
      throw new IllegalArgumentException("processId cannot be blank");
    }

    if (operationId.isBlank()) {
      throw new IllegalArgumentException("operationId cannot be blank");
    }

    if (checkpointVersion <= 0) {
      throw new IllegalArgumentException(
          "checkpointVersion must be positive, was: " + checkpointVersion);
    }
  }
}
