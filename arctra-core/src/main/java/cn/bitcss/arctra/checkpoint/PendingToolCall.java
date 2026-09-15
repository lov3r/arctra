package cn.bitcss.arctra.checkpoint;

import java.util.Objects;

/**
 * Durable representation of a pending tool call.
 *
 * <p>Framework-neutral DTO for checkpoint persistence. Contains the minimal identity required to
 * reconstruct Spring AI tool-calling continuation after runtime boundary.
 *
 * <p>This is a <strong>durable semantic contract</strong> - changes affect checkpoint schema
 * compatibility.
 *
 * <h2>M6-T3A: Logical Operation Identity</h2>
 *
 * <p>operationId represents the Arctra framework's logical durable tool operation identity. It is:
 *
 * <ul>
 *   <li>Framework-owned (not provider-specific)
 *   <li>Stable across concurrent resume attempts for the same logical operation
 *   <li>Distinct for each logical operation, even same-name tools
 *   <li>Generated once when PendingToolCall is first materialized
 *   <li>Durable before physical execution is possible
 * </ul>
 *
 * <p>operationId and toolCallId are DISTINCT identities with DIFFERENT semantics and lifecycle:
 *
 * <ul>
 *   <li><strong>operationId</strong>: Arctra logical tool operation (framework-owned, durable,
 *       recovery-critical)
 *   <li><strong>toolCallId</strong>: Spring AI protocol correlation (provider-owned, ephemeral
 *       within one model conversation)
 * </ul>
 *
 * @param operationId Arctra framework operation identity (opaque, non-null, non-blank)
 * @param toolCallId Spring AI tool call identifier (must be preserved exactly)
 * @param toolName tool name for dispatch
 * @param arguments tool input as JSON string
 * @author lov3r
 * @since M5 (operationId added in M6-T3A)
 */
public record PendingToolCall(
    String operationId, String toolCallId, String toolName, String arguments) {

  public PendingToolCall {
    if (operationId == null || operationId.isBlank()) {
      throw new IllegalArgumentException("operationId cannot be null or blank");
    }
    if (toolCallId == null || toolCallId.isBlank()) {
      throw new IllegalArgumentException("toolCallId cannot be null or blank");
    }
    if (toolName == null || toolName.isBlank()) {
      throw new IllegalArgumentException("toolName cannot be null or blank");
    }
    Objects.requireNonNull(arguments, "arguments cannot be null");
  }
}
