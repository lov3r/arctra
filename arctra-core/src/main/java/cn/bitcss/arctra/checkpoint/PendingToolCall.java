package cn.bitcss.arctra.checkpoint;

import java.util.List;
import java.util.Objects;

/**
 * Durable representation of a pending tool call.
 *
 * <p>Framework-neutral DTO for checkpoint persistence. Contains only the minimal protocol identity
 * required to reconstruct Spring AI tool-calling continuation after runtime boundary.
 *
 * <p>This is a <strong>durable semantic contract</strong> - changes affect checkpoint schema
 * compatibility.
 *
 * @param toolCallId Spring AI tool call identifier (must be preserved exactly)
 * @param toolName tool name for dispatch
 * @param arguments tool input as JSON string
 * @author lov3r
 * @since M5
 */
public record PendingToolCall(String toolCallId, String toolName, String arguments) {

  public PendingToolCall {
    if (toolCallId == null || toolCallId.isBlank()) {
      throw new IllegalArgumentException("toolCallId cannot be null or blank");
    }
    if (toolName == null || toolName.isBlank()) {
      throw new IllegalArgumentException("toolName cannot be null or blank");
    }
    Objects.requireNonNull(arguments, "arguments cannot be null");
  }
}
