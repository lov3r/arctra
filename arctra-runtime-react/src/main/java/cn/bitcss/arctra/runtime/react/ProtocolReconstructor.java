package cn.bitcss.arctra.runtime.react;

import cn.bitcss.arctra.checkpoint.PendingToolCall;
import cn.bitcss.arctra.evidence.Evidence;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;

/**
 * Protocol reconstructor for durable resumed execution.
 *
 * <p>Reconstructs Spring AI tool-calling protocol from checkpoint-backed pending tool batch.
 *
 * <p><strong>M6-T3B: Direct Per-Operation Execution:</strong> Replaces ToolCallingManager batch
 * execution with explicit per-operation invocation to establish authoritative operationId
 * correlation at delegate.call() boundary.
 *
 * <p><strong>M6-T4A: Invocation Intent Gate:</strong> Records durable invocation intent immediately
 * before physical delegate invocation. Intent persistence failure blocks execution (hard gate).
 *
 * <p><strong>Responsibilities:</strong>
 *
 * <ul>
 *   <li>AssistantMessage.ToolCall reconstruction (preserving toolCallIds)
 *   <li>Per-operation tool execution with operationId correlation
 *   <li>Invocation intent recording (M6-T4A pre-call gate)
 *   <li>ToolResponseMessage construction
 *   <li>Conversation continuation message sequence
 * </ul>
 *
 * <p><strong>Protocol Flow:</strong>
 *
 * <pre>
 * PendingToolCall batch (from checkpoint)
 *   → reconstruct AssistantMessage with ToolCalls
 *   → execute each operation directly (M6-T3B)
 *     → record invocation intent (M6-T4A hard gate)
 *     → delegate.call() (only after successful intent recording)
 *   → build ToolResponseMessage with responses
 *   → return continuation: history + AssistantMessage + ToolResponseMessage
 * </pre>
 *
 * <p>Package-private. Not part of public API.
 *
 * @author lov3r
 * @since M5 (M6-T3B refactored to direct execution, M6-T4A added intent gate)
 */
class ProtocolReconstructor {

  private final List<ToolCallback> tools;
  private final InvocationStateStore invocationStateStore;

  /**
   * Construct protocol reconstructor.
   *
   * @param tools tool callbacks for execution
   * @param invocationStateStore invocation-state store for intent recording (M6-T4A)
   */
  ProtocolReconstructor(List<ToolCallback> tools, InvocationStateStore invocationStateStore) {
    this.tools = tools;
    this.invocationStateStore = invocationStateStore;
  }

  /**
   * Reconstruct and execute approved pending tool batch (M6-T3B: with per-operation event emission).
   *
   * <p>Executes the exact original pending batch from checkpoint. Tools execute with NEW callback
   * instances (dispatch by name). Evidence captured during execution.
   *
   * <p><strong>Governance bypass:</strong> Pending batch already passed governance with
   * REQUIRE_APPROVAL. This execution does NOT re-evaluate governance for the original batch.
   *
   * <p><strong>M6-T3B Per-Operation Context:</strong> Base observation context provides
   * resume-level correlation. Per-operation contexts created during execution with specific operationId.
   *
   * @param pendingBatch pending tool calls from checkpoint
   * @param conversationHistory current conversation messages
   * @param checkpointEvidences accumulated evidences from checkpoint (not used, preserved for future)
   * @param newEvidences sink for new evidence from this execution
   * @param baseObservationContext base resume context (processId, checkpointVersion, eventListener) or null
   * @return continuation messages with ToolResponseMessages
   */
  List<Message> executeApprovedBatch(
      List<PendingToolCall> pendingBatch,
      List<Message> conversationHistory,
      List<Evidence> checkpointEvidences,
      List<Evidence> newEvidences,
      ToolObservationContext baseObservationContext) {

    return executeApprovedBatchInternal(
        pendingBatch, conversationHistory, checkpointEvidences, newEvidences, baseObservationContext);
  }

  /**
   * Execute approved Spring AI tool batch without event observation.
   *
   * <p>For non-durable executions or tests not concerned with tool events.
   *
   * <p><strong>M6-T4A.1: REMOVED - Bypasses mandatory invocation gate.</strong>
   *
   * <p>This method previously allowed null observationContext, which would bypass the mandatory
   * INVOCATION_INTENT recording gate. Since M6-T4A established that checkpoint-backed durable
   * execution MUST record invocation intent, the no-context overload is unsafe.
   *
   * <p>Callers must use the 5-parameter overload with proper ToolObservationContext.
   *
   * @deprecated Removed in M6-T4A.1 - bypasses mandatory invocation gate
   * @throws UnsupportedOperationException always
   */
  @Deprecated
  List<Message> executeApprovedBatch(
      List<PendingToolCall> pendingBatch,
      List<Message> conversationHistory,
      List<Evidence> checkpointEvidences,
      List<Evidence> newEvidences) {

    throw new UnsupportedOperationException(
        "executeApprovedBatch without ToolObservationContext is no longer supported. "
            + "M6-T4A requires mandatory INVOCATION_INTENT recording with processId and operationId. "
            + "Use the 5-parameter overload with proper ToolObservationContext.");
  }

  /**
   * Internal implementation for approved batch execution.
   *
   * <p><strong>M6-T3B: Direct Per-Operation Execution:</strong>
   *
   * <p>Replaces ToolCallingManager with explicit per-operation execution to establish authoritative
   * operationId correlation at delegate.call() boundary.
   *
   * <p>For each PendingToolCall:
   *
   * <ol>
   *   <li>Resolve ToolCallback by toolName
   *   <li>Create per-operation observation context with operationId
   *   <li>Wrap with EvidenceCapturingToolCallback
   *   <li>Invoke delegate.call() directly
   *   <li>Emit TOOL_EXECUTED(operationId) or TOOL_FAILED(operationId)
   *   <li>Construct ToolResponseMessage.ToolResponse with original toolCallId
   * </ol>
   *
   * <p><strong>Semantic Parity:</strong> Preserves Spring AI ToolCallingManager behavior:
   *
   * <ul>
   *   <li>Tool selection by toolName
   *   <li>Missing tool handling (IllegalStateException)
   *   <li>Sequential execution (preserves order)
   *   <li>Exception propagation from delegate
   *   <li>ToolResponseMessage construction with toolCallId
   *   <li>ToolContext created per invocation
   * </ul>
   *
   * @param baseObservationContext base resume context (processId, checkpointVersion, eventListener) or null
   */
  private List<Message> executeApprovedBatchInternal(
      List<PendingToolCall> pendingBatch,
      List<Message> conversationHistory,
      List<Evidence> checkpointEvidences,
      List<Evidence> newEvidences,
      ToolObservationContext baseObservationContext) {

    // Reconstruct AssistantMessage with original ToolCalls (for protocol compliance)
    List<AssistantMessage.ToolCall> toolCalls =
        pendingBatch.stream()
            .<AssistantMessage.ToolCall>map(
                dto ->
                    new AssistantMessage.ToolCall(
                        dto.toolCallId(), // Preserve exact toolCallId
                        "function", // Type (Spring AI standard)
                        dto.toolName(), // Preserve exact toolName
                        dto.arguments())) // Preserve exact arguments
            .toList();

    AssistantMessage rebuiltAssistantMessage =
        AssistantMessage.builder()
            .content("") // Content not critical for tool execution protocol
            .toolCalls(toolCalls)
            .build();

    // M6-T3B: Direct per-operation execution
    List<ToolResponseMessage.ToolResponse> toolResponses = new ArrayList<>();

    for (PendingToolCall operation : pendingBatch) {
      ToolResponseMessage.ToolResponse response =
          executeOperation(operation, conversationHistory, newEvidences, baseObservationContext);
      toolResponses.add(response);
    }

    // Build ToolResponseMessage with responses
    ToolResponseMessage toolResponseMessage =
        ToolResponseMessage.builder().responses(toolResponses).build();

    // Return continuation messages: history + AssistantMessage + ToolResponseMessage
    List<Message> result = new ArrayList<>(conversationHistory);
    result.add(rebuiltAssistantMessage);
    result.add(toolResponseMessage);

    return result;
  }

  /**
   * Execute one tool operation with authoritative operationId correlation.
   *
   * <p><strong>M6-T3B: Core Per-Operation Execution:</strong>
   *
   * <p>Establishes exact 1:1 mapping:
   *
   * <pre>
   * PendingToolCall(operationId=OP, toolCallId=TC)
   *   → resolve ToolCallback by toolName
   *   → create per-operation ToolObservationContext(operationId=OP)
   *   → wrap with EvidenceCapturingToolCallback
   *   → M6-T4A: recordInvocationIntent(processId, operationId) ← HARD GATE
   *   → delegate.call(arguments, toolContext) ← ONLY AFTER SUCCESSFUL INTENT
   *   → emit TOOL_EXECUTED(OP) or TOOL_FAILED(OP)
   *   → return ToolResponse(toolCallId=TC, result)
   * </pre>
   *
   * <p><strong>M6-T4A: Invocation Intent Gate:</strong>
   *
   * <p>Before physical invocation, durable invocation intent is recorded. If persistence fails,
   * execution is blocked. This is a hard gate, not best-effort observation.
   *
   * <p><strong>M6-T4A.1: operationContext is MANDATORY:</strong>
   *
   * <p>operationContext must be non-null. It provides the recovery-critical processId and
   * operationId required for INVOCATION_INTENT recording. If null, this method will fail fast
   * with NullPointerException from invocationStateStore.recordInvocationIntent().
   *
   * <p><strong>Semantic Parity with ToolCallingManager:</strong>
   *
   * <ul>
   *   <li>Tool resolution: by toolName from registered tools
   *   <li>Missing tool: IllegalStateException (framework error, not tool failure)
   *   <li>ToolContext: created per invocation with conversation history
   *   <li>Exception handling: delegate exception propagates (becomes TOOL_FAILED via wrapper)
   *   <li>Response construction: preserves original toolCallId for protocol
   * </ul>
   *
   * @param operation one pending tool call with operationId
   * @param conversationHistory current conversation for ToolContext
   * @param newEvidences evidence sink
   * @param baseObservationContext base resume context (processId, checkpointVersion, eventListener) - MUST be non-null for M6-T4A
   * @return ToolResponse with original toolCallId
   * @throws IllegalStateException if tool not found (framework error)
   * @throws NullPointerException if baseObservationContext is null (M6-T4A.1 invariant)
   * @throws InvocationIntentPersistenceException if intent persistence fails (gate failure)
   * @throws RuntimeException if delegate.call() throws (becomes TOOL_FAILED)
   */
  private ToolResponseMessage.ToolResponse executeOperation(
      PendingToolCall operation,
      List<Message> conversationHistory,
      List<Evidence> newEvidences,
      ToolObservationContext baseObservationContext) {

    // Resolve ToolCallback by toolName
    ToolCallback selectedTool =
        tools.stream()
            .filter(tool -> tool.getToolDefinition().name().equals(operation.toolName()))
            .findFirst()
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Tool not found: " + operation.toolName() + " (framework resolution failure)"));

    // M6-T4A.1: baseObservationContext is MANDATORY for durable execution
    // It provides recovery-critical processId and operationId for INVOCATION_INTENT gate
    Objects.requireNonNull(
        baseObservationContext,
        "ToolObservationContext is mandatory for checkpoint-backed durable execution (M6-T4A.1). "
            + "Cannot record INVOCATION_INTENT without processId and operationId.");

    // Create per-operation observation context with operationId
    ToolObservationContext operationContext =
        new ToolObservationContext(
            baseObservationContext.processId(),
            baseObservationContext.checkpointVersion(),
            operation.operationId(), // M6-T3B: Per-operation identity
            baseObservationContext.eventListener());

    // Wrap with Evidence capture + tool event observation
    EvidenceCapturingToolCallback wrappedCallback =
        new EvidenceCapturingToolCallback(selectedTool, newEvidences, operationContext);

    // M6-T4A: INVOCATION INTENT GATE (hard gate before physical invocation)
    // MANDATORY for checkpoint-backed durable execution - no bypass allowed
    invocationStateStore.recordInvocationIntent(
        operationContext.processId(), operation.operationId());
    // If recordInvocationIntent throws InvocationIntentPersistenceException,
    // execution stops here. Physical invocation MUST NOT proceed.

    // Execute tool with ToolContext (semantic parity with ToolCallingManager)
    // Spring AI 2.0.0: ToolContext is Map<String, Object>, pass conversation history
    ToolContext toolContext = new ToolContext(java.util.Map.of("conversationHistory", conversationHistory));

    // Physical invocation - only reached after successful intent recording
    // EvidenceCapturingToolCallback emits TOOL_EXECUTED(operationId) or TOOL_FAILED(operationId)
    String result = wrappedCallback.call(operation.arguments(), toolContext);

    // Construct ToolResponse preserving original toolCallId (protocol correlation)
    return new ToolResponseMessage.ToolResponse(
        operation.toolCallId(), // Protocol identity
        operation.toolName(),
        result);
  }

  /**
   * Construct protocol-valid denial for rejected pending batch.
   *
   * <p>Zero tools execute. Zero new Evidence. Constructs ToolResponseMessages with denial content
   * preserving original toolCallIds.
   *
   * @param pendingBatch pending tool calls from checkpoint (rejected)
   * @param conversationHistory current conversation messages
   * @return continuation messages with denial responses
   */
  List<Message> constructDenialResponses(
      List<PendingToolCall> pendingBatch, List<Message> conversationHistory) {

    // Reconstruct AssistantMessage with original ToolCalls
    List<AssistantMessage.ToolCall> toolCalls =
        pendingBatch.stream()
            .<AssistantMessage.ToolCall>map(
                dto ->
                    new AssistantMessage.ToolCall(
                        dto.toolCallId(), "function", dto.toolName(), dto.arguments()))
            .toList();

    AssistantMessage rebuiltAssistantMessage =
        AssistantMessage.builder().content("").toolCalls(toolCalls).build();

    // Build ToolResponseMessage with denial for each tool call
    List<org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse> denialResponses =
        toolCalls.stream()
            .map(
                tc ->
                    new org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse(
                        tc.id(), // Preserve toolCallId
                        tc.name(),
                        "Tool execution rejected by approval decision"))
            .toList();

    org.springframework.ai.chat.messages.ToolResponseMessage toolResponseMessage =
        org.springframework.ai.chat.messages.ToolResponseMessage.builder()
            .responses(denialResponses)
            .build();

    // Return continuation messages: history + AssistantMessage + denial ToolResponseMessage
    List<Message> result = new java.util.ArrayList<>(conversationHistory);
    result.add(rebuiltAssistantMessage);
    result.add(toolResponseMessage);
    return result;
  }
}
