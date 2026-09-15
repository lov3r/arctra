package cn.bitcss.arctra.runtime.react;

import cn.bitcss.arctra.evidence.Evidence;
import cn.bitcss.arctra.execution.EventType;
import cn.bitcss.arctra.execution.ExecutionEvent;
import cn.bitcss.arctra.execution.ExecutionEventListener;
import java.util.List;
import java.util.Objects;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/**
 * ToolCallback wrapper that captures evidence during tool execution.
 *
 * <p>This is an internal helper for per-execution evidence collection. Each execution creates
 * fresh wrappers with a local evidence list.
 *
 * <p>Transparently proxies all ToolCallback methods to ensure evidence is captured regardless of
 * which call path Spring AI uses.
 *
 * <p><strong>M6-T2B Event Emission:</strong> Optionally emits TOOL_EXECUTED / TOOL_FAILED events
 * for checkpoint-backed approved resume executions. Event emission requires stable processId from
 * checkpoint.
 *
 * <p>Public for testing purposes, but primarily intended for internal use by
 * SpringAiToolCallingEngine.
 *
 * @author lov3r
 */
public class EvidenceCapturingToolCallback implements ToolCallback {

  private final ToolCallback delegate;
  private final List<Evidence> evidences;

  // M6-T2B: Optional event emission context (null = no events)
  private final ToolObservationContext observationContext; // nullable

  /**
   * Create evidence-capturing wrapper (M4 / backward compatibility).
   *
   * <p>No event emission (ephemeral execution or no checkpoint correlation).
   *
   * @param delegate the actual ToolCallback to wrap
   * @param evidences evidence collection sink
   */
  public EvidenceCapturingToolCallback(ToolCallback delegate, List<Evidence> evidences) {
    this(delegate, evidences, null);
  }

  /**
   * Create evidence-capturing wrapper with event emission (M6-T2B, refactored in R2).
   *
   * <p>Emits TOOL_EXECUTED / TOOL_FAILED events for checkpoint-backed approved resume executions.
   *
   * <p>If observationContext is null, event emission is disabled (ephemeral or no checkpoint
   * correlation).
   *
   * <p><strong>M6-T2B Note:</strong> toolCallId is NOT included in payload. Tool events contain
   * only toolName. Spring AI's ToolResponseMessages already preserve toolCallId for protocol
   * reconstruction.
   *
   * @param delegate the actual ToolCallback to wrap
   * @param evidences evidence collection sink
   * @param observationContext tool observation context (null = no events)
   */
  EvidenceCapturingToolCallback(
      ToolCallback delegate, List<Evidence> evidences, ToolObservationContext observationContext) {

    this.delegate = Objects.requireNonNull(delegate, "delegate cannot be null");
    this.evidences = Objects.requireNonNull(evidences, "evidences cannot be null");

    // Event context (nullable - null = no event emission)
    this.observationContext = observationContext;
  }

  @Override
  public ToolDefinition getToolDefinition() {
    return delegate.getToolDefinition();
  }

  @Override
  public ToolMetadata getToolMetadata() {
    return delegate.getToolMetadata();
  }

  @Override
  public String call(String functionArguments) {
    String toolName = delegate.getToolDefinition().name();

    // M6-T2B: Delegate invocation boundary
    // CRITICAL: Only delegate.call() is inside try - Evidence/event failures cannot become
    // TOOL_FAILED
    String result;
    try {
      result = delegate.call(functionArguments);
    } catch (Exception toolFailure) {
      // Domain fact TRUE: delegate ToolCallback threw
      // TOOL_FAILED is TRUE

      // Emit TOOL_FAILED event (if event context available)
      emitToolFailedEvent(toolName);

      // DO NOT capture evidence on failure (M4 original behavior)
      // Evidence is only for successful tool executions

      // Re-throw original tool exception
      throw toolFailure;
    }

    // Domain fact TRUE: delegate ToolCallback returned normally
    // TOOL_EXECUTED is TRUE

    // Emit TOOL_EXECUTED event (if event context available)
    emitToolExecutedEvent(toolName);

    // Capture successful evidence (preserve existing behavior)
    captureEvidence(toolName, result);

    return result;
  }

  @Override
  public String call(String functionArguments, ToolContext toolContext) {
    String toolName = delegate.getToolDefinition().name();

    // M6-T2B: Delegate invocation boundary
    String result;
    try {
      result = delegate.call(functionArguments, toolContext);
    } catch (Exception toolFailure) {
      // TOOL_FAILED is TRUE
      emitToolFailedEvent(toolName);
      // DO NOT capture evidence on failure
      throw toolFailure;
    }

    // TOOL_EXECUTED is TRUE
    emitToolExecutedEvent(toolName);
    captureEvidence(toolName, result);

    return result;
  }

  /**
   * Emit TOOL_EXECUTED event (M6-T2B).
   *
   * <p>Only emitted if event context is available. Emission failure does not affect tool execution
   * success.
   */
  private void emitToolExecutedEvent(String toolName) {
    if (observationContext == null) {
      return; // Event emission disabled (no checkpoint correlation)
    }

    try {
      String payload = buildToolEventPayload(toolName);
      observationContext.eventListener().onEvent(
          new ExecutionEvent(
              observationContext.processId(),
              EventType.TOOL_EXECUTED,
              observationContext.checkpointVersion(),
              payload));
    } catch (Exception emissionFailure) {
      // Event emission failure does not affect tool execution fact
      // CompositeExecutionEventListener should already isolate this,
      // but defensive catch here ensures no propagation
    }
  }

  /**
   * Emit TOOL_FAILED event (M6-T2B).
   *
   * <p>Only emitted if event context is available. Emission failure does not affect tool execution
   * failure.
   */
  private void emitToolFailedEvent(String toolName) {
    if (observationContext == null) {
      return; // Event emission disabled
    }

    try {
      String payload = buildToolEventPayload(toolName);
      observationContext.eventListener().onEvent(
          new ExecutionEvent(
              observationContext.processId(),
              EventType.TOOL_FAILED,
              observationContext.checkpointVersion(),
              payload));
    } catch (Exception emissionFailure) {
      // Event emission failure does not affect tool execution fact
    }
  }

  /**
   * Build minimal tool event payload (M6-T2B).
   *
   * <p>Contains only: toolName (required).
   *
   * <p>Does NOT contain: toolCallId, arguments, result, error message, errorType, duration.
   *
   * <p><strong>Rationale:</strong> Spring AI's ToolResponseMessages already preserve toolCallId
   * for protocol reconstruction. Emitting it in events creates duplicate correlation burden and
   * architectural complexity (per-invocation wrapper vs per-tool wrapper). Tool event purpose is
   * observability of "which tool executed", not "which specific call instance".
   */
  private String buildToolEventPayload(String toolName) {
    // Minimal safe JSON construction (no external dependency)
    // toolName is framework-controlled (from ToolDefinition.name())
    return String.format("{\"toolName\":\"%s\"}", escapeJson(toolName));
  }

  /**
   * Capture evidence (M4 existing behavior, preserved).
   *
   * <p>Only called after successful tool execution. Failure to capture evidence does not affect
   * tool execution result.
   */
  private void captureEvidence(String toolName, String result) {
    try {
      String source = "tool:" + toolName;
      evidences.add(new Evidence(source, result));
    } catch (Exception e) {
      // Evidence capture failure does not affect tool execution
      // Log suppression intentional - evidence is best-effort observability
    }
  }

  /**
   * Escape JSON string values.
   *
   * <p>Minimal escaping for framework-controlled values. Handles: quotes, backslashes, control
   * characters.
   */
  private String escapeJson(String value) {
    return value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t");
  }
}
