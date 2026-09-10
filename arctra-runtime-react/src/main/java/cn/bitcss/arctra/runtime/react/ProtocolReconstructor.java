package cn.bitcss.arctra.runtime.react;

import cn.bitcss.arctra.checkpoint.PendingToolCall;
import cn.bitcss.arctra.evidence.Evidence;
import java.util.List;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;

/**
 * Spring AI tool-calling protocol reconstruction from durable checkpoint.
 *
 * <p>Package-private production seam for reconstructing Spring AI tool protocol from
 * framework-neutral checkpoint state. Proven by M5-T2 PoC.
 *
 * <h2>Reconstruction Path</h2>
 *
 * <pre>
 * PendingToolCall DTO
 *   → Spring AI AssistantMessage with ToolCalls
 *   → ToolCallingManager.executeToolCalls()
 *   → ToolResponseMessage with preserved toolCallIds
 *   → continuation messages
 * </pre>
 *
 * <h2>Invariants</h2>
 *
 * <ul>
 *   <li>Original toolCallId preserved exactly
 *   <li>NEW tool callback instances used (dispatch by name)
 *   <li>Evidence captured during execution
 *   <li>Spring AI handles all tool execution mechanics
 * </ul>
 *
 * @author lov3r
 * @since M5
 */
class ProtocolReconstructor {

  private final List<ToolCallback> tools;
  private final ToolCallingManager toolCallingManager;

  ProtocolReconstructor(List<ToolCallback> tools) {
    this.tools = tools;
    this.toolCallingManager = ToolCallingManager.builder().build();
  }

  /**
   * Reconstruct and execute approved pending tool batch.
   *
   * <p>Executes the exact original pending batch from checkpoint. Tools execute with NEW callback
   * instances (dispatch by name). Evidence captured during execution.
   *
   * <p><strong>Governance bypass:</strong> Pending batch already passed governance with
   * REQUIRE_APPROVAL. This execution does NOT re-evaluate governance for the original batch.
   *
   * @param pendingBatch pending tool calls from checkpoint
   * @param conversationHistory current conversation messages from ChatMemory
   * @param checkpointEvidences accumulated evidences from checkpoint
   * @param newEvidences synchronized list to collect new execution evidences
   * @return continuation messages after tool execution
   */
  List<Message> executeApprovedBatch(
      List<PendingToolCall> pendingBatch,
      List<Message> conversationHistory,
      List<Evidence> checkpointEvidences,
      List<Evidence> newEvidences) {

    // Reconstruct AssistantMessage with original ToolCalls
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

    // Reconstruct ChatResponse
    ChatResponse rebuiltChatResponse =
        new ChatResponse(List.of(new Generation(rebuiltAssistantMessage)));

    // Wrap tools with evidence capturing
    // newEvidences is an EMPTY sink for this-resume-only evidences
    // Historical evidences from checkpoint are NOT copied here
    // Merge happens exactly once in resumeProcess() orchestrator
    List<ToolCallback> wrappedTools =
        tools.stream()
            .map(tool -> new EvidenceCapturingToolCallback(tool, newEvidences))
            .map(wrapper -> (ToolCallback) wrapper)
            .toList();

    // Build Prompt with wrapped tools
    ToolCallingChatOptions optionsWithTools =
        ToolCallingChatOptions.builder().toolCallbacks(wrappedTools).build();

    Prompt promptWithTools = new Prompt(conversationHistory, optionsWithTools);

    // Execute ALL tools in batch via ToolCallingManager
    // Spring AI handles dispatch, execution, and ToolResponseMessage construction
    ToolExecutionResult toolExecutionResult =
        toolCallingManager.executeToolCalls(promptWithTools, rebuiltChatResponse);

    // Return continuation messages with ToolResponseMessages
    // These preserve original toolCallIds automatically
    return toolExecutionResult.conversationHistory();
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
