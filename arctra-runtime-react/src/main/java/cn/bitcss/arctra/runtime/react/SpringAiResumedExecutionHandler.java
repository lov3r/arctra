package cn.bitcss.arctra.runtime.react;

import cn.bitcss.arctra.agent.AgentDefinition;
import cn.bitcss.arctra.agent.AgentExecutionContext;
import cn.bitcss.arctra.checkpoint.PendingToolCall;
import cn.bitcss.arctra.evidence.Evidence;
import cn.bitcss.arctra.process.ContinuationSignal;
import cn.bitcss.arctra.runtime.RuntimeBinding;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;

/**
 * Spring AI resumed execution handler.
 *
 * <p>Executes approved or rejected tool batches using Spring AI and continues model conversation.
 * Returns execution mechanism outcome without performing durable state transitions.
 *
 * <p><strong>M6-T4A: Invocation Intent Gate:</strong> Delegates to ProtocolReconstructor which
 * records durable invocation intent before physical tool execution.
 *
 * <p><strong>Responsibilities:</strong>
 *
 * <ul>
 *   <li>Spring AI protocol reconstruction (approved/rejected)
 *   <li>Tool execution via ProtocolReconstructor (if approved)
 *   <li>Evidence collection from tool execution
 *   <li>Model continuation via ChatClient
 *   <li>Governance evaluation during continuation
 *   <li>Detecting new tool calls requiring approval
 *   <li>Returning execution outcome (completed or governance suspended)
 * </ul>
 *
 * <p><strong>Explicitly does NOT own:</strong>
 *
 * <ul>
 *   <li>CHECK A (checkpoint load/validation)
 *   <li>CHECK B (deleteIfVersion / replaceIfVersion)
 *   <li>Checkpoint version progression
 *   <li>SUSPENDED / COMPLETED durable event emission
 *   <li>ProcessFactory invocation
 *   <li>CheckpointStore access
 *   <li>RuntimeBindingResolver access
 *   <li>ExecutionLedger access
 *   <li>Invocation intent recording (delegated to ProtocolReconstructor)
 * </ul>
 *
 * <p>Package-private internal collaborator. Not part of public API.
 *
 * @author lov3r
 * @since M6-T2.5A-R3 (M6-T4A added invocation intent gate)
 */
final class SpringAiResumedExecutionHandler implements ResumedExecutionHandler {

  private final ChatModel chatModel;
  private final ChatMemory chatMemory;
  private final List<ToolCallback> tools;
  private final cn.bitcss.arctra.governance.ToolGovernancePolicy governancePolicy;
  private final InvocationStateStore invocationStateStore;

  /**
   * Create Spring AI resumed execution handler.
   *
   * @param chatModel Spring AI chat model
   * @param chatMemory Spring AI chat memory
   * @param tools tool callbacks
   * @param governancePolicy tool governance policy
   * @param invocationStateStore invocation-state store for intent recording (M6-T4A)
   */
  SpringAiResumedExecutionHandler(
      ChatModel chatModel,
      ChatMemory chatMemory,
      List<ToolCallback> tools,
      cn.bitcss.arctra.governance.ToolGovernancePolicy governancePolicy,
      InvocationStateStore invocationStateStore) {

    this.chatModel = Objects.requireNonNull(chatModel, "chatModel cannot be null");
    this.chatMemory = Objects.requireNonNull(chatMemory, "chatMemory cannot be null");
    this.tools = Objects.requireNonNull(tools, "tools cannot be null");
    this.governancePolicy =
        Objects.requireNonNull(governancePolicy, "governancePolicy cannot be null");
    this.invocationStateStore =
        Objects.requireNonNull(invocationStateStore, "invocationStateStore cannot be null");
  }

  /**
   * Execute resumed execution and return mechanism outcome.
   *
   * <p>Performs:
   *
   * <ol>
   *   <li>Protocol reconstruction (approved or rejected)
   *   <li>Tool execution (if approved, via ToolCallingManager)
   *   <li>Model continuation (ChatClient with governance)
   *   <li>Detection of new governance suspension or completion
   * </ol>
   *
   * <p>Returns execution mechanism outcome. Durable state transitions remain caller responsibility.
   *
   * @param pendingBatch pending tool calls from checkpoint
   * @param binding runtime binding (definition + context)
   * @param checkpointEvidences accumulated evidences from checkpoint
   * @param signal continuation signal (approved/rejected)
   * @param observationContext tool observation context for event emission
   * @return execution outcome (model completed or governance suspended)
   */
  @Override
  public ResumedExecutionOutcome executeResume(
      List<PendingToolCall> pendingBatch,
      RuntimeBinding binding,
      List<Evidence> checkpointEvidences,
      ContinuationSignal signal,
      ToolObservationContext observationContext) {

    // Get conversation history from ChatMemory
    List<Message> conversationHistory = getConversationHistory(binding.context());

    // New evidence sink (synchronized for ToolCallingManager concurrency)
    List<Evidence> newEvidences = Collections.synchronizedList(new ArrayList<>());

    // Reconstruct protocol messages based on signal
    List<Message> continuationMessages;
    if (signal instanceof ContinuationSignal.ApprovalSignal approval) {
      if (approval.approved()) {
        // APPROVED: execute pending batch
        continuationMessages =
            reconstructAndExecuteApproved(
                pendingBatch,
                conversationHistory,
                checkpointEvidences,
                newEvidences,
                observationContext);
      } else {
        // REJECTED: construct denial responses
        continuationMessages = reconstructDenialResponses(pendingBatch, conversationHistory);
      }
    } else {
      throw new IllegalArgumentException(
          "Unknown ContinuationSignal type: " + signal.getClass().getName());
    }

    // Continue with model (pass context for session ID)
    try {
      String content = continueWithModel(continuationMessages, binding.definition(), binding.context(), newEvidences);

      // Model completed normally - merge evidences
      List<Evidence> mergedEvidences = new ArrayList<>(checkpointEvidences);
      mergedEvidences.addAll(newEvidences);

      return new ResumedExecutionOutcome.ModelCompleted(content, mergedEvidences);

    } catch (ToolApprovalRequiredSignal suspensionSignal) {
      // New tool calls require approval - merge evidences
      List<Evidence> mergedEvidences = new ArrayList<>(checkpointEvidences);
      mergedEvidences.addAll(newEvidences);

      // Convert Spring AI AssistantMessage to framework-neutral PendingToolCall
      List<PendingToolCall> newPendingBatch = convertToPendingBatch(
          suspensionSignal.state().assistantMessageWithToolCalls());

      return new ResumedExecutionOutcome.GovernanceSuspended(newPendingBatch, mergedEvidences);
    }
  }

  /**
   * Convert Spring AI AssistantMessage tool calls to framework-neutral PendingToolCall list.
   *
   * <p>Preserves tool call identity (toolCallId) to maintain protocol correlation for duplicate
   * same-name tools.
   *
   * @param suspendedAssistant Spring AI assistant message with tool calls
   * @return framework-neutral pending tool call list
   */
  private List<PendingToolCall> convertToPendingBatch(AssistantMessage suspendedAssistant) {
    return suspendedAssistant.getToolCalls().stream()
        .map(
            tc ->
                new PendingToolCall(
                    OperationIds.generate(), // operationId - NEW operation (M6-T3A)
                    tc.id(),                 // toolCallId - preserves protocol identity
                    tc.name(),               // toolName
                    tc.arguments()))         // arguments JSON
        .toList();
  }

  /**
   * Reconstruct and execute approved pending batch.
   *
   * <p>M6-T4A: ProtocolReconstructor records invocation intent before physical execution.
   *
   * @return continuation messages with ToolResponseMessages
   */
  private List<Message> reconstructAndExecuteApproved(
      List<PendingToolCall> pendingBatch,
      List<Message> conversationHistory,
      List<Evidence> checkpointEvidences,
      List<Evidence> newEvidences,
      ToolObservationContext observationContext) {

    ProtocolReconstructor reconstructor = new ProtocolReconstructor(tools, invocationStateStore);

    return reconstructor.executeApprovedBatch(
        pendingBatch, conversationHistory, checkpointEvidences, newEvidences, observationContext);
  }

  /**
   * Reconstruct denial responses for rejected batch.
   *
   * @return continuation messages with denial ToolResponseMessages
   */
  private List<Message> reconstructDenialResponses(
      List<PendingToolCall> pendingBatch, List<Message> conversationHistory) {

    ProtocolReconstructor reconstructor = new ProtocolReconstructor(tools, invocationStateStore);

    return reconstructor.constructDenialResponses(pendingBatch, conversationHistory);
  }

  /**
   * Continue model conversation with governance.
   *
   * @return final assistant content
   * @throws ToolApprovalRequiredSignal if new tool calls require approval
   */
  private String continueWithModel(
      List<Message> continuationMessages, AgentDefinition definition, AgentExecutionContext context, List<Evidence> newEvidences) {

    // Create tool calling manager
    org.springframework.ai.model.tool.ToolCallingManager toolCallingManager =
        org.springframework.ai.model.tool.ToolCallingManager.builder().build();

    // Create governance advisor
    GovernanceToolCallingAdvisor governanceAdvisor =
        new GovernanceToolCallingAdvisor(
            tools, governancePolicy, context, toolCallingManager);

    try {
      // Build ChatClient with advisors (NO MessageChatMemoryAdvisor - ChatMemory written after CHECK B)
      ChatClient chatClient =
          ChatClient.builder(chatModel).defaultAdvisors(governanceAdvisor).build();

      // Construct messages: definition + continuationMessages
      List<Message> allMessages = new ArrayList<>();
      allMessages.add(new SystemMessage(definition.description()));
      allMessages.addAll(continuationMessages);

      // Wrap tools for Evidence capture (no tool observation - continuation uses normal path)
      List<ToolCallback> evidenceWrappedTools =
          tools.stream()
              .map(tool -> new EvidenceCapturingToolCallback(tool, newEvidences))
              .map(wrapper -> (ToolCallback) wrapper)
              .toList();

      // Execute with tools (no session ID advisor - memory written after CHECK B)

        return chatClient.prompt().messages(allMessages).tools(evidenceWrappedTools).call().content();

    } finally {
      governanceAdvisor.clearState();
    }
  }

  /**
   * Get conversation history from ChatMemory.
   */
  private List<Message> getConversationHistory(AgentExecutionContext context) {
    String sessionId = context.sessionId();
    if (sessionId != null) {
      return chatMemory.get(sessionId);
    }
    return List.of();
  }

  /**
   * Persist final completed assistant message to ChatMemory.
   *
   * <p>Called by orchestration layer after CHECK B deleteIfVersion succeeds.
   *
   * @param context execution context with session ID
   * @param content final assistant content
   */
  @Override
  public void persistCompletedAssistant(AgentExecutionContext context, String content) {
    String sessionId = context.sessionId();
    if (sessionId != null) {
      AssistantMessage finalMessage = AssistantMessage.builder().content(content).build();
      chatMemory.add(sessionId, finalMessage);
    }
  }
}
