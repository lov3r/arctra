package cn.bitcss.arctra.runtime.react;

import cn.bitcss.arctra.agent.AgentExecutionContext;
import cn.bitcss.arctra.evidence.Evidence;
import cn.bitcss.arctra.governance.GovernanceDecision;
import cn.bitcss.arctra.governance.ToolGovernancePolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;

/**
 * Governance-aware Tool Calling Advisor.
 *
 * <p>Implements tool-calling loop with governance interception within Spring AI Advisor chain.
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
public class GovernanceToolCallingAdvisor implements CallAdvisor {

  // Use same order as Spring AI's ToolCallingAdvisor
  // This ensures we are positioned correctly in the advisor chain
  private static final int DEFAULT_ORDER = -2147483348;
  private static final int MAX_ITERATIONS = 10;

  private final List<ToolCallback> tools;
  private final ToolGovernancePolicy governancePolicy;
  private final AgentExecutionContext executionContext;

  // Thread-local state for suspension and evidence
  private final ThreadLocal<SuspensionState> suspensionState = new ThreadLocal<>();
  private final ThreadLocal<List<Evidence>> evidences = new ThreadLocal<>();

  public GovernanceToolCallingAdvisor(
      List<ToolCallback> tools,
      ToolGovernancePolicy governancePolicy,
      AgentExecutionContext executionContext) {
    this.tools = tools;
    this.governancePolicy = governancePolicy;
    this.executionContext = executionContext;
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

    // Initialize state (preserves any pre-set evidences)
    if (evidences.get() == null) {
      evidences.set(new ArrayList<>());
    }
    suspensionState.remove();

    // Tool calling loop
    ChatClientRequest currentRequest = request;
    int iteration = 0;

    while (iteration < MAX_ITERATIONS) {
      // Call next in chain using copy (like Spring AI ToolCallingAdvisor does)
      // This ensures the chain can properly reach the ChatModel call
      ChatClientResponse response = chain.copy(this).nextCall(currentRequest);
      ChatResponse chatResponse = response.chatResponse();

      // Check for tool calls
      Generation result = chatResponse.getResult();
      if (result == null || result.getOutput() == null) {
        return response;
      }

      AssistantMessage assistantMessage = (AssistantMessage) result.getOutput();

      if (assistantMessage.getToolCalls() == null
          || assistantMessage.getToolCalls().isEmpty()) {
        // No tool calls → final answer
        return response;
      }

      // Tool call detected
      var toolCall = assistantMessage.getToolCalls().get(0); // M4: single tool call
      String toolCallId = toolCall.id();
      String toolName = toolCall.name();
      String toolArguments = toolCall.arguments();

      // Governance evaluation
      GovernanceDecision decision =
          governancePolicy.evaluate(toolName, toolArguments, executionContext);

      switch (decision) {
        case ALLOW -> {
          // Execute tool
          String toolResult = executeTool(toolName, toolArguments);
          evidences.get().add(new Evidence("tool:" + toolName, toolResult));

          // Build next request
          currentRequest =
              buildNextRequest(currentRequest, assistantMessage, toolCallId, toolName, toolResult);
        }

        case DENY -> {
          // Inject denial
          String denialMessage = String.format("Tool '%s' invocation denied by policy", toolName);
          currentRequest =
              buildNextRequest(
                  currentRequest, assistantMessage, toolCallId, toolName, denialMessage);
        }

        case REQUIRE_APPROVAL -> {
          // Capture suspension state
          suspensionState.set(
              new SuspensionState(
                  currentRequest,
                  assistantMessage,
                  toolCallId,
                  toolName,
                  toolArguments,
                  new ArrayList<>(evidences.get())));

          // Return suspended response
          return buildSuspendedResponse(toolName);
        }
      }

      iteration++;
    }

    throw new IllegalStateException("Tool calling loop exceeded max iterations: " + MAX_ITERATIONS);
  }

  private ChatClientRequest buildNextRequest(
      ChatClientRequest originalRequest,
      AssistantMessage assistantMessage,
      String toolCallId,
      String toolName,
      String result) {

    // Get current messages from prompt
    List<Message> messages = new ArrayList<>(originalRequest.prompt().getInstructions());

    // Append assistant message + tool response
    messages.add(assistantMessage);
    messages.add(
        ToolResponseMessage.builder()
            .responses(List.of(new ToolResponseMessage.ToolResponse(toolCallId, toolName, result)))
            .build());

    // Build new prompt
    Prompt newPrompt = new Prompt(messages);

    // Build new request
    return ChatClientRequest.builder()
        .prompt(newPrompt)
        .context(originalRequest.context())
        .build();
  }

  private ChatClientResponse buildSuspendedResponse(String toolName) {
    String content = String.format("Execution suspended: tool '%s' requires approval", toolName);
    AssistantMessage message = new AssistantMessage(content);
    Generation generation = new Generation(message);
    ChatResponse chatResponse = new ChatResponse(List.of(generation));
    return new ChatClientResponse(chatResponse, Map.of());
  }

  private String executeTool(String toolName, String toolArguments) {
    ToolCallback tool =
        tools.stream()
            .filter(t -> t.getToolDefinition().name().equals(toolName))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("Tool not found: " + toolName));

    return tool.call(toolArguments);
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

  /** Get suspension state if execution suspended (returns null if not suspended). */
  public SuspensionState getSuspensionState() {
    return suspensionState.get();
  }

  /** Clear thread-local state. */
  public void clearState() {
    evidences.remove();
    suspensionState.remove();
  }

  /**
   * Suspension state captured when REQUIRE_APPROVAL.
   *
   * <p>Package-visible for SpringAiToolCallingEngine.
   */
  public record SuspensionState(
      ChatClientRequest originalRequest,
      AssistantMessage assistantMessageWithToolCall,
      String toolCallId,
      String toolName,
      String toolArguments,
      List<Evidence> evidences) {}
}
