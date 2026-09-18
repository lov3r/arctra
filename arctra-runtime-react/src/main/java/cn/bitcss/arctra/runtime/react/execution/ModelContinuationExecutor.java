package cn.bitcss.arctra.runtime.react.execution;

import cn.bitcss.arctra.agent.AgentDefinition;
import cn.bitcss.arctra.agent.AgentExecutionContext;
import cn.bitcss.arctra.agent.AgentResult;
import cn.bitcss.arctra.evidence.Evidence;
import cn.bitcss.arctra.governance.ToolGovernancePolicy;
import cn.bitcss.arctra.runtime.react.governance.GovernanceToolCallingAdvisor;
import cn.bitcss.arctra.runtime.react.protocol.ToolApprovalRequiredSignal;
import cn.bitcss.arctra.runtime.react.tool.EvidenceCapturingToolCallback;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;

/**
 * Handles model continuation after tool execution.
 *
 * <p><strong>M6-T6.4 Execution Flow Decomposition</strong>
 *
 * <p>Unified model continuation logic for:
 *
 * <ul>
 *   <li>Ephemeral resume (approved/denied)
 *   <li>Durable continuation (after tool execution)
 *   <li>Protocol-preserved message continuation
 * </ul>
 *
 * <p><strong>Responsibility:</strong>
 *
 * <ul>
 *   <li>Execute next Spring AI model turn from prepared protocol state
 *   <li>Tool execution via ToolCallingManager (for resume paths)
 *   <li>ChatClient invocation with governance advisors
 *   <li>ChatMemory persistence
 *   <li>Evidence collection
 * </ul>
 *
 * <p><strong>Does NOT own:</strong>
 *
 * <ul>
 *   <li>Checkpoint creation/deletion
 *   <li>Recovery classification
 *   <li>Governance policy evaluation
 * </ul>
 *
 * @author lov3r
 * @since M6-T6.4
 */
public class ModelContinuationExecutor {

  private final ChatModel chatModel;
  private final List<ToolCallback> tools;
  private final ChatMemory chatMemory;
  private final ToolGovernancePolicy governancePolicy;

  public ModelContinuationExecutor(
      ChatModel chatModel,
      List<ToolCallback> tools,
      ChatMemory chatMemory,
      ToolGovernancePolicy governancePolicy) {
    this.chatModel = chatModel;
    this.tools = tools;
    this.chatMemory = chatMemory;
    this.governancePolicy = governancePolicy;
  }

  /**
   * Execute ChatClient with messages (unified for initial and continuation execution).
   *
   * <p><strong>M6-T6.4: Unified ChatClient execution.</strong>
   *
   * <p>Used by:
   * <ul>
   *   <li>SpringAiToolCallingEngine.execute() (initial execution with system + user messages)
   *   <li>continueWithMessages() (continuation with full protocol history)
   * </ul>
   *
   * @param messages messages to execute (system+user for initial, full history for continuation)
   * @param evidences accumulated evidences
   * @param definition agent definition
   * @param context execution context
   * @param wrappedTools evidence-wrapped tools
   * @param governanceAdvisor governance advisor (already configured)
   * @param includeMemoryAdvisor whether to include MessageChatMemoryAdvisor (true for initial, false for continuation)
   * @return agent result
   */
  public AgentResult executeWithMessages(
      List<Message> messages,
      List<Evidence> evidences,
      AgentDefinition definition,
      AgentExecutionContext context,
      List<ToolCallback> wrappedTools,
      GovernanceToolCallingAdvisor governanceAdvisor,
      boolean includeMemoryAdvisor) {

    // Pre-initialize evidences in governance advisor
    governanceAdvisor.initializeEvidences(evidences);

    // Build ChatClient
    var chatClient = ChatClient.builder(chatModel).build();

    // Execute via ChatClient
    String sessionId = context.sessionId();
    var promptSpec = chatClient
        .prompt()
        .messages(messages)
        .tools(wrappedTools.toArray(new ToolCallback[0]))
        .advisors(spec -> {
          // Disable auto-registration of ToolCallingAdvisor
          spec.param("spring.ai.chat.client.tool.calling.advisor.auto-register", false);

          // Add MessageChatMemoryAdvisor for initial execution only
          if (includeMemoryAdvisor && sessionId != null) {
            var memoryAdvisor = org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor
                .builder(chatMemory).build();
            spec.advisors(memoryAdvisor);
            spec.param(org.springframework.ai.chat.memory.ChatMemory.CONVERSATION_ID, sessionId);
          }

          // Add GovernanceToolCallingAdvisor
          spec.advisors(governanceAdvisor);
        });

    // Execute
    String content = promptSpec.call().content();

    // For continuation (includeMemoryAdvisor=false), manually persist final assistant message
    if (!includeMemoryAdvisor && sessionId != null) {
      chatMemory.add(sessionId, new org.springframework.ai.chat.messages.AssistantMessage(content));
    }

    return new AgentResult(content, evidences);
  }

  /**
   * Resume approved: execute all tools in batch and continue model.
   *
   * <p>Used by ephemeral resume path.
   *
   * @param suspensionState governance suspension state
   * @param previousEvidences accumulated evidences before suspension
   * @param definition agent definition
   * @param context execution context
   * @return agent result (may be suspended again)
   */
  public AgentResult resumeApproved(
      GovernanceToolCallingAdvisor.SuspensionState suspensionState,
      List<Evidence> previousEvidences,
      AgentDefinition definition,
      AgentExecutionContext context) {

    // Wrap tools with evidence capturing for this resume execution
    List<Evidence> newEvidences = Collections.synchronizedList(new ArrayList<>(previousEvidences));
    List<ToolCallback> wrappedTools =
        tools.stream()
            .map(tool -> new EvidenceCapturingToolCallback(tool, newEvidences))
            .map(wrapper -> (ToolCallback) wrapper)
            .toList();

    // Reconstruct ChatResponse with the original AssistantMessage containing all ToolCalls
    AssistantMessage assistantMessage = suspensionState.assistantMessageWithToolCalls();
    Generation generation = new Generation(assistantMessage);
    ChatResponse chatResponse = new ChatResponse(List.of(generation));

    // Build Prompt with wrapped tools
    ToolCallingChatOptions optionsWithTools =
        ToolCallingChatOptions.builder().toolCallbacks(wrappedTools).build();

    Prompt promptWithTools =
        new Prompt(suspensionState.originalRequest().prompt().getInstructions(), optionsWithTools);

    // Create ToolCallingManager
    ToolCallingManager toolCallingManager = ToolCallingManager.builder().build();

    // Execute ALL tools in the batch via ToolCallingManager
    ToolExecutionResult toolExecutionResult =
        toolCallingManager.executeToolCalls(promptWithTools, chatResponse);

    // Check returnDirect semantics
    if (toolExecutionResult.returnDirect()) {
      // Tool result should be returned directly to client
      ChatResponse directResponse =
          ChatResponse.builder()
              .from(chatResponse)
              .generations(ToolExecutionResult.buildGenerations(toolExecutionResult))
              .build();

      String content = Objects.requireNonNull(directResponse.getResult()).getOutput().getText();

      // Completed via returnDirect - persist final answer
      persistCompletedAssistant(context, content);

      return new AgentResult(content, newEvidences);
    }

    // Use Spring AI's conversation history for continuation
    List<Message> continuationMessages = toolExecutionResult.conversationHistory();

    // Continue execution via ChatClient with protocol-preserved messages
    return continueWithMessages(continuationMessages, newEvidences, definition, context);
  }

  /**
   * Resume denied: construct protocol-valid denial responses and continue model.
   *
   * <p>Used by ephemeral resume path.
   *
   * @param suspensionState governance suspension state
   * @param evidences accumulated evidences
   * @param definition agent definition
   * @param context execution context
   * @return agent result (may be suspended again)
   */
  public AgentResult resumeDenied(
      GovernanceToolCallingAdvisor.SuspensionState suspensionState,
      List<Evidence> evidences,
      AgentDefinition definition,
      AgentExecutionContext context) {

    // REJECTED: construct protocol-valid denial responses for entire batch
    // 0 tools execute, 0 Evidence added

    AssistantMessage assistantMessage = suspensionState.assistantMessageWithToolCalls();

    // Build ToolResponseMessage with denial for each tool call
    List<ToolResponseMessage.ToolResponse> denialResponses =
        assistantMessage.getToolCalls().stream()
            .map(
                tc ->
                    new ToolResponseMessage.ToolResponse(
                        tc.id(), tc.name(), "Tool execution rejected by approval decision"))
            .toList();

    // Build continuation messages preserving native protocol
    List<Message> continuationMessages =
        new ArrayList<>(suspensionState.originalRequest().prompt().getInstructions());
    continuationMessages.add(assistantMessage);
    continuationMessages.add(ToolResponseMessage.builder().responses(denialResponses).build());

    // Continue execution via ChatClient with protocol-preserved messages
    // No new evidences - rejected batch executes 0 tools
    return continueWithMessages(continuationMessages, evidences, definition, context);
  }

  /**
   * Continue execution with protocol-preserved messages.
   *
   * <p>This method re-enters the ChatClient/Advisor execution path with native Spring AI Messages,
   * preserving tool-calling protocol (AssistantMessage with ToolCalls, ToolResponseMessages with
   * toolCallIds).
   *
   * <p>This is the correct continuation mechanism after tool execution, as opposed to flattening
   * messages to text and restarting via AgentRequest.
   *
   * <p><strong>M6-T6.4: Public for unified continuation.</strong> Used by:
   *
   * <ul>
   *   <li>resumeApproved/resumeDenied (ephemeral resume)
   *   <li>DurableContinuationExecutor (durable auto-continue)
   * </ul>
   *
   * @param messages native Spring AI messages (including ToolResponseMessage)
   * @param evidences accumulated evidences
   * @param definition agent definition
   * @param context execution context
   * @return result (may be suspended again if another REQUIRE_APPROVAL is encountered)
   */
  public AgentResult continueWithMessages(
      List<Message> messages,
      List<Evidence> evidences,
      AgentDefinition definition,
      AgentExecutionContext context) {

    // Create ToolCallingManager for Spring AI tool execution mechanics
    var toolCallingManager = ToolCallingManager.builder().build();

    // Wrap tools with evidence capturing - reuse the same evidence list
    List<ToolCallback> wrappedTools =
        tools.stream()
            .map(tool -> new EvidenceCapturingToolCallback(tool, evidences))
            .map(wrapper -> (ToolCallback) wrapper)
            .toList();

    // Create governance advisor with wrapped tools
    var governanceAdvisor =
        new GovernanceToolCallingAdvisor(wrappedTools, governancePolicy, context, toolCallingManager);

    try {
      // M6-T6.4: Use unified executeWithMessages
      // includeMemoryAdvisor=false because messages already contain full history
      return executeWithMessages(messages, evidences, definition, context, wrappedTools, governanceAdvisor, false);

    } catch (ToolApprovalRequiredSignal signal) {
      // Re-suspension - caller must handle
      throw signal;

    } finally {
      governanceAdvisor.clearState();
    }
  }

  /**
   * Build system instruction from agent definition.
   *
   * @param definition agent definition
   * @return system instruction string
   */
  public String buildSystemInstruction(AgentDefinition definition) {
    var name = definition.name();
    var description = definition.description();

    if (description == null || description.isBlank()) {
      return String.format("You are %s.", name);
    } else {
      return String.format("You are %s. %s", name, description);
    }
  }

  /**
   * Persist final Assistant message to ChatMemory when execution completes.
   *
   * <p><b>Memory Semantic:</b>
   *
   * <ul>
   *   <li>Initial execution: MessageChatMemoryAdvisor handles full H + U + A persistence
   *   <li>Resumed execution: User message already persisted during suspension; this method closes
   *       the open turn by persisting only final Assistant message
   * </ul>
   *
   * @param context execution context (contains sessionId)
   * @param content final assistant message content
   */
  private void persistCompletedAssistant(AgentExecutionContext context, String content) {
    String sessionId = context.sessionId();
    if (sessionId != null) {
      chatMemory.add(sessionId, new AssistantMessage(content));
    }
  }
}
