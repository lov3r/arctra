package cn.bitcss.arctra.runtime.react;

import cn.bitcss.arctra.agent.AgentExecutionContext;
import cn.bitcss.arctra.evidence.Evidence;
import cn.bitcss.arctra.governance.GovernanceDecision;
import cn.bitcss.arctra.governance.ToolGovernancePolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;

/**
 * Governance-aware Tool Calling Advisor.
 *
 * <p>Implements tool-calling loop with governance interception within Spring AI Advisor chain.
 *
 * <h2>Two-Phase Execution</h2>
 *
 * <p>Phase 1: Governance Preflight - evaluate ALL tool calls, execute NONE
 * <p>Phase 2: Batch execution based on decision precedence (DENY > REQUIRE_APPROVAL > ALLOW)
 *
 * <h2>Ownership Boundary</h2>
 *
 * <p>Arctra owns: governance evaluation, decision precedence, suspension, approval semantics
 * <p>Spring AI owns: tool resolution, execution mechanics, ToolExecutionResult, returnDirect
 *
 * <h2>Integration with Advisor Chain</h2>
 *
 * <p>This advisor works correctly with MessageChatMemoryAdvisor and other advisors. The chain
 * order ensures memory is loaded before tool calling and persisted after.
 *
 * <h2>Suspension Mechanism</h2>
 *
 * <p>When REQUIRE_APPROVAL is detected, suspension state is captured via thread-local and
 * retrieved by SpringAiToolCallingEngine for Process materialization.
 *
 * @author lov3r
 * @since M4
 */
@NullMarked
@SuppressWarnings("nullness")  // Spring AI context Map uses @Nullable values
class GovernanceToolCallingAdvisor implements CallAdvisor {

  // Use same order as Spring AI's ToolCallingAdvisor
  // This ensures we are positioned correctly in the advisor chain
  private static final int DEFAULT_ORDER = -2147483348;
  private static final int MAX_ITERATIONS = 10;

  private final ToolGovernancePolicy governancePolicy;
  private final AgentExecutionContext executionContext;
  private final ToolCallingManager toolCallingManager;

  // Thread-local state for evidence collection only
  private final ThreadLocal<@Nullable List<Evidence>> evidences = new ThreadLocal<>();

  public GovernanceToolCallingAdvisor(
      List<ToolCallback> tools,
      ToolGovernancePolicy governancePolicy,
      AgentExecutionContext executionContext,
      ToolCallingManager toolCallingManager) {
    // tools parameter kept for backward compatibility but not used
    // Following Spring AI pattern: ToolCallingChatOptions comes from the prompt
    this.governancePolicy = governancePolicy;
    this.executionContext = executionContext;
    this.toolCallingManager = toolCallingManager;
  }

  @Override
  public String getName() {
    return "GovernanceToolCallingAdvisor";
  }

  @Override
  public int getOrder() {
    return DEFAULT_ORDER;
  }

  @Override
  public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {

    // Initialize evidence state (preserves any pre-set evidences)
    if (evidences.get() == null) {
      evidences.set(new ArrayList<>());
    }

    // Preserve original context (contains advisor params like CONVERSATION_ID)
    // Must be passed to all returned responses for MessageChatMemoryAdvisor.after()
    Map<String, @Nullable Object> originalContext = request.context();

    // Extract ChatOptions from original prompt
    ChatOptions originalOptions = request.prompt().getOptions();

    // Following Spring AI's ToolCallingAdvisor pattern:
    // Only activate if ToolCallingChatOptions is present
    // If no tool calling options, skip governance and pass through
    if (!(originalOptions instanceof ToolCallingChatOptions toolCallingChatOptions)) {
      // No tool calling options - skip the governance tool calling advisor
      // This allows non-tool scenarios to work without overhead
      return chain.nextCall(request);
    }

    // Tool calling loop
    ChatClientRequest currentRequest = request;
    int iteration = 0;

    while (iteration < MAX_ITERATIONS) {
      // Call next in chain using copy (like Spring AI ToolCallingAdvisor does)
      // This ensures the chain can properly reach the ChatModel call
      ChatClientResponse response = chain.copy(this).nextCall(currentRequest);

      ChatResponse chatResponse = response.chatResponse();

      // Check for tool calls
        assert chatResponse != null;
        Generation result = chatResponse.getResult();
      if (result == null) {
        return ensureContext(response, originalContext);
      }

      AssistantMessage assistantMessage = result.getOutput();

      if (assistantMessage.getToolCalls().isEmpty()) {
        // No tool calls → final answer
        return ensureContext(response, originalContext);
      }

      // ============================================================
      // PHASE 1: GOVERNANCE PREFLIGHT
      // Evaluate ALL tool calls BEFORE executing ANY tool
      // ============================================================

      List<AssistantMessage.ToolCall> toolCalls = assistantMessage.getToolCalls();
      List<GovernanceDecision> decisions = new ArrayList<>();

      for (AssistantMessage.ToolCall toolCall : toolCalls) {
        GovernanceDecision decision = governancePolicy.evaluate(
            toolCall.name(),
            toolCall.arguments(),
            executionContext
        );
        decisions.add(decision);
      }

      // ============================================================
      // PHASE 2: APPLY DECISION PRECEDENCE
      // DENY > REQUIRE_APPROVAL > ALLOW
      // ============================================================

      // Check for DENY (highest precedence)
      boolean hasDeny = decisions.contains(GovernanceDecision.DENY);
      if (hasDeny) {
        // CASE B: ANY DENY → entire batch denied, 0 tools executed
        // Construct protocol-valid denial responses and continue to next iteration
        currentRequest = handleDeniedBatch(currentRequest, assistantMessage, toolCalls, originalContext, toolCallingChatOptions);
        iteration++;
        continue;  // Let model observe denial and adapt
      }

      // Check for REQUIRE_APPROVAL (second precedence)
      boolean hasRequireApproval = decisions.contains(GovernanceDecision.REQUIRE_APPROVAL);
      if (hasRequireApproval) {
        // CASE C: ANY REQUIRE_APPROVAL → suspend entire batch, 0 tools executed
        return handleSuspendedBatch(currentRequest, assistantMessage, originalContext);
      }

      // ============================================================
      // CASE A: ALL ALLOW → delegate to Spring AI ToolCallingManager
      // ============================================================

      ToolExecutionResult toolExecutionResult =
          toolCallingManager.executeToolCalls(currentRequest.prompt(), chatResponse);

      // Check returnDirect semantics
      if (toolExecutionResult.returnDirect()) {
        // Tool result should be returned directly to client, not sent back to model
        ChatResponse directResponse = ChatResponse.builder()
            .from(chatResponse)
            .generations(ToolExecutionResult.buildGenerations(toolExecutionResult))
            .build();
        return ensureContext(buildResponseWithContext(directResponse, originalContext), originalContext);
      }

      // Use Spring AI's conversation history for next iteration
      List<Message> nextInstructions = toolExecutionResult.conversationHistory();

      // Build next request with updated messages
      // CRITICAL: must pass toolCallingChatOptions to preserve tool definitions (if available)
      Prompt nextPrompt = (toolCallingChatOptions != null)
          ? new Prompt(nextInstructions, toolCallingChatOptions)
          : new Prompt(nextInstructions);

      currentRequest = ChatClientRequest.builder()
          .prompt(nextPrompt)
          .context(originalContext)
          .build();

      iteration++;
    }

    throw new IllegalStateException("Tool calling loop exceeded max iterations: " + MAX_ITERATIONS);
  }

  /**
   * Ensures the response contains the original context (with advisor params like CONVERSATION_ID).
   * This is critical for MessageChatMemoryAdvisor.after() to work correctly.
   */
  private ChatClientResponse ensureContext(ChatClientResponse response, Map<String, @Nullable Object> originalContext) {
    // If response already has the correct context, return as-is
    if (response.context().equals(originalContext)) {
      return response;
    }
    // Otherwise, rebuild with original context
      assert response.chatResponse() != null;
      return buildResponseWithContext(response.chatResponse(), originalContext);
  }

  /**
   * Safe builder for ChatClientResponse with nullable context map.
   * Suppresses nullness warning at single point.
   */
  @SuppressWarnings("nullness")
  private ChatClientResponse buildResponseWithContext(
      ChatResponse chatResponse,
      Map<String, @Nullable Object> context) {
    return new ChatClientResponse(chatResponse, context);
  }

  /**
   * Handle DENY decision: construct protocol-valid denial responses for entire batch.
   * NO tools execute. 0 Evidence.
   * Returns updated request with denial responses to continue protocol.
   */
  private ChatClientRequest handleDeniedBatch(
      ChatClientRequest currentRequest,
      AssistantMessage assistantMessage,
      List<AssistantMessage.ToolCall> toolCalls,
      Map<String, @Nullable Object> originalContext,
      @Nullable ToolCallingChatOptions toolCallingChatOptions) {

    // Construct ToolResponseMessage with denial for each tool call
    // This preserves protocol: N ToolCalls → N ToolResponses
    List<ToolResponseMessage.ToolResponse> denialResponses = toolCalls.stream()
        .map(tc -> new ToolResponseMessage.ToolResponse(
            tc.id(),
            tc.name(),
            "Tool execution denied by governance policy"
        ))
        .toList();

    ToolResponseMessage denialMessage = ToolResponseMessage.builder()
        .responses(denialResponses)
        .build();

    // Build next request with AssistantMessage + ToolResponseMessage
    List<Message> nextInstructions = new ArrayList<>(currentRequest.prompt().getInstructions());
    nextInstructions.add(assistantMessage);
    nextInstructions.add(denialMessage);

    // CRITICAL: must pass toolCallingChatOptions to preserve tool definitions (if available)
    Prompt nextPrompt = (toolCallingChatOptions != null)
        ? new Prompt(nextInstructions, toolCallingChatOptions)
        : new Prompt(nextInstructions);

    return ChatClientRequest.builder()
        .prompt(nextPrompt)
        .context(originalContext)
        .build();
  }

  /**
   * Handle REQUIRE_APPROVAL decision: suspend entire batch.
   * NO tools execute before approval.
   * M4 approval granularity = entire AssistantMessage ToolCall Batch.
   *
   * <p>Throws {@link ToolApprovalRequiredSignal} to suspend execution without generating
   * synthetic AssistantMessage placeholder. This prevents MessageChatMemoryAdvisor from
   * persisting suspension state as conversational content.
   */
  private ChatClientResponse handleSuspendedBatch(
      ChatClientRequest currentRequest,
      AssistantMessage assistantMessage,
      Map<String, @Nullable Object> originalContext) {

    // Construct suspension state - entire batch, not individual tool
    SuspensionState state = new SuspensionState(
        currentRequest,
        assistantMessage,  // Contains all ToolCalls
        new ArrayList<>(Objects.requireNonNull(evidences.get()))
    );

    // Throw internal control signal - suspension is Process state, not conversational message
    throw new ToolApprovalRequiredSignal(state);
  }

  /** Get collected evidences (must be called from same thread). */
  public List<Evidence> getEvidences() {
    List<Evidence> result = evidences.get();
    return result != null ? new ArrayList<>(result) : List.of();
  }

  /**
   * Pre-initialize evidences before adviseCall().
   * Used by continuation path to restore evidences from previous suspension.
   */
  void initializeEvidences(List<Evidence> initialEvidences) {
    evidences.set(new ArrayList<>(initialEvidences));
  }

  /** Clear thread-local state. */
  public void clearState() {
    evidences.remove();
  }

  /**
   * Suspension state captured when REQUIRE_APPROVAL.
   *
   * <p>Represents the entire tool call batch, not individual tools.
   * M4 approval granularity = AssistantMessage ToolCall Batch.
   *
   * <p>Package-visible for SpringAiToolCallingEngine.
   *
   * <p>Carried by {@link ToolApprovalRequiredSignal} - no longer stored in ThreadLocal.
   */
  record SuspensionState(
      ChatClientRequest originalRequest,
      AssistantMessage assistantMessageWithToolCalls,
      List<Evidence> evidences) {}
}
