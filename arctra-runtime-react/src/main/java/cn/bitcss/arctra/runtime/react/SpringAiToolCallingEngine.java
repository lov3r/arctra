package cn.bitcss.arctra.runtime.react;

import cn.bitcss.arctra.agent.AgentDefinition;
import cn.bitcss.arctra.agent.AgentExecutionContext;
import cn.bitcss.arctra.agent.AgentRequest;
import cn.bitcss.arctra.agent.AgentResult;
import cn.bitcss.arctra.evidence.Evidence;
import cn.bitcss.arctra.governance.ToolGovernancePolicy;
import cn.bitcss.arctra.process.AgentProcess;
import cn.bitcss.arctra.process.ContinuationSignal;
import cn.bitcss.arctra.process.ContinuationSignal.ApprovalSignal;
import cn.bitcss.arctra.runtime.AgentExecutionEngine;
import cn.bitcss.arctra.runtime.ProcessFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;

/**
 * Agent execution engine based on Spring AI with Governance support.
 *
 * <p>Uses Spring AI ChatClient + Advisor chain with custom {@link GovernanceToolCallingAdvisor} for
 * governance-aware tool execution.
 *
 * <p><strong>M2 Evolution:</strong> Supports multi-turn conversation continuity via {@link
 * AgentExecutionContext}. When {@code context.sessionId()} is present, uses {@link
 * MessageChatMemoryAdvisor} to inject conversation history and persist new messages.
 *
 * <p><strong>M4 Evolution:</strong> Supports governance-driven suspension. When a tool invocation
 * requires approval, execution suspends and returns {@link AgentResult} with {@link AgentProcess}.
 *
 * <h2>Advisor Chain</h2>
 *
 * <p>Execution order:
 *
 * <ol>
 *   <li>MessageChatMemoryAdvisor (before) - loads session history
 *   <li>GovernanceToolCallingAdvisor - tool calling loop + governance
 *   <li>MessageChatMemoryAdvisor (after) - persists new messages
 * </ol>
 *
 * @author lov3r
 */
public class SpringAiToolCallingEngine implements AgentExecutionEngine {

  private final ChatModel chatModel;
  private final List<ToolCallback> tools;
  private final ChatMemory chatMemory;
  private final ToolGovernancePolicy governancePolicy;

  /**
   * Create a tool-calling engine with conversation memory and governance support.
   *
   * @param chatModel the chat model for agent execution
   * @param tools the tools available to the agent
   * @param chatMemory the chat memory for conversation history (shared across executions)
   * @param governancePolicy the governance policy for tool invocations
   */
  public SpringAiToolCallingEngine(
      ChatModel chatModel,
      List<ToolCallback> tools,
      ChatMemory chatMemory,
      ToolGovernancePolicy governancePolicy) {
    this.chatModel = Objects.requireNonNull(chatModel, "chatModel cannot be null");
    this.tools = Objects.requireNonNull(tools, "tools cannot be null");
    this.chatMemory = Objects.requireNonNull(chatMemory, "chatMemory cannot be null");
    this.governancePolicy =
        Objects.requireNonNull(governancePolicy, "governancePolicy cannot be null");
  }

  /**
   * M2 backward compatible constructor (no governance).
   *
   * @param chatModel the chat model
   * @param tools the tools
   * @param chatMemory the chat memory
   */
  public SpringAiToolCallingEngine(
      ChatModel chatModel, List<ToolCallback> tools, ChatMemory chatMemory) {
    this(chatModel, tools, chatMemory, ToolGovernancePolicy.allowAll());
  }

  @Override
  public AgentResult execute(
      AgentDefinition definition, AgentRequest request, AgentExecutionContext context) {

    // Create governance tool calling advisor
    var governanceAdvisor = new GovernanceToolCallingAdvisor(tools, governancePolicy, context);

    try {
      // Build ChatClient (without adding advisors to builder to avoid defaults)
      var chatClient = ChatClient.builder(chatModel).build();

      // Construct system instruction
      var systemInstruction = buildSystemInstruction(definition);

      // Execute via ChatClient - explicitly specify advisors at prompt level
      String sessionId = context.sessionId();
      var promptSpec = chatClient.prompt()
          .system(systemInstruction)
          .user(request.userMessage())
          .advisors(spec -> {
            // Disable auto-registration of ToolCallingAdvisor
            spec.param("spring.ai.chat.client.tool.calling.advisor.auto-register", false);

            // Explicitly add only the advisors we want
            if (sessionId != null) {
              var memoryAdvisor = MessageChatMemoryAdvisor.builder(chatMemory).build();
              spec.advisors(memoryAdvisor);
              spec.param(ChatMemory.CONVERSATION_ID, sessionId);
            }
            spec.advisors(governanceAdvisor);
          });

      // Execute
      var content = promptSpec.call().content();

      // Check if suspension occurred
      var suspensionState = governanceAdvisor.getSuspensionState();
      if (suspensionState != null) {
        // Build suspended result with Process
        return suspendForApproval(
            suspensionState, governanceAdvisor.getEvidences(), definition, context);
      }

      // Normal completion
      return new AgentResult(content, governanceAdvisor.getEvidences());

    } finally {
      // Cleanup thread-local state
      governanceAdvisor.clearState();
    }
  }

  private AgentResult suspendForApproval(
      GovernanceToolCallingAdvisor.SuspensionState suspensionState,
      List<Evidence> evidences,
      AgentDefinition definition,
      AgentExecutionContext context) {

    // Create continuation function for resume
    var continuationFunction =
        new java.util.function.Function<ContinuationSignal, AgentResult>() {
          @Override
          public AgentResult apply(ContinuationSignal signal) {
            if (signal instanceof ApprovalSignal approval) {
              if (approval.approved()) {
                return resumeApproved(suspensionState, evidences, definition, context);
              } else {
                return resumeDenied(suspensionState, evidences, definition, context);
              }
            }
            throw new IllegalArgumentException("Unknown ContinuationSignal type: " + signal);
          }
        };

    // Materialize Process
    AgentProcess process = ProcessFactory.createSuspended(continuationFunction);

    // Return suspended result
    String partialContent =
        String.format(
            "Execution suspended: tool '%s' requires approval", suspensionState.toolName());
    return new AgentResult(partialContent, evidences, process);
  }

  private AgentResult resumeApproved(
      GovernanceToolCallingAdvisor.SuspensionState suspensionState,
      List<Evidence> previousEvidences,
      AgentDefinition definition,
      AgentExecutionContext context) {

    // Execute pending tool
    String toolResult = executeTool(suspensionState.toolName(), suspensionState.toolArguments());

    // Capture evidence
    List<Evidence> updatedEvidences = new ArrayList<>(previousEvidences);
    updatedEvidences.add(new Evidence("tool:" + suspensionState.toolName(), toolResult));

    // Build continuation messages preserving native protocol
    List<Message> continuationMessages =
        new ArrayList<>(suspensionState.originalRequest().prompt().getInstructions());

    continuationMessages.add(suspensionState.assistantMessageWithToolCall());
    continuationMessages.add(
        ToolResponseMessage.builder()
            .responses(
                List.of(
                    new ToolResponseMessage.ToolResponse(
                        suspensionState.toolCallId(), suspensionState.toolName(), toolResult)))
            .build());

    // Continue execution via ChatClient with protocol-preserved messages
    return continueWithMessages(continuationMessages, updatedEvidences, definition, context);
  }

  private AgentResult resumeDenied(
      GovernanceToolCallingAdvisor.SuspensionState suspensionState,
      List<Evidence> evidences,
      AgentDefinition definition,
      AgentExecutionContext context) {

    String denialMessage =
        String.format(
            "Tool '%s' invocation rejected by approval decision", suspensionState.toolName());

    // Build continuation messages preserving native protocol
    List<Message> continuationMessages =
        new ArrayList<>(suspensionState.originalRequest().prompt().getInstructions());
    continuationMessages.add(suspensionState.assistantMessageWithToolCall());
    continuationMessages.add(
        ToolResponseMessage.builder()
            .responses(
                List.of(
                    new ToolResponseMessage.ToolResponse(
                        suspensionState.toolCallId(), suspensionState.toolName(), denialMessage)))
            .build());

    // Continue execution via ChatClient with protocol-preserved messages
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
   * @param messages native Spring AI messages (including ToolResponseMessage)
   * @param evidences accumulated evidences
   * @param definition agent definition
   * @param context execution context
   * @return result (may be suspended again if another REQUIRE_APPROVAL is encountered)
   */
  private AgentResult continueWithMessages(
      List<Message> messages,
      List<Evidence> evidences,
      AgentDefinition definition,
      AgentExecutionContext context) {

    // Create governance advisor (will accumulate new evidences if any)
    var governanceAdvisor = new GovernanceToolCallingAdvisor(tools, governancePolicy, context);

    // Pre-initialize evidences with previous ones before adviseCall()
    // (GovernanceToolCallingAdvisor.adviseCall() will preserve pre-set evidences)
    governanceAdvisor.initializeEvidences(evidences);

    try {
      // Build ChatClient
      var chatClient = ChatClient.builder(chatModel).build();

      // Construct system instruction
      var systemInstruction = buildSystemInstruction(definition);

      // Continue execution via ChatClient with protocol-preserved messages
      String sessionId = context.sessionId();
      var promptSpec =
          chatClient
              .prompt()
              // NOTE: Do NOT add .system() here - messages already contains SystemMessage
              // from originalRequest. Adding it again would cause duplication.
              .messages(messages) // ← Use native Messages (includes SystemMessage)
              .advisors(
                  spec -> {
                    // Disable auto-registration of ToolCallingAdvisor
                    spec.param(
                        "spring.ai.chat.client.tool.calling.advisor.auto-register", false);

                    // Add memory advisor if session exists
                    if (sessionId != null) {
                      var memoryAdvisor = MessageChatMemoryAdvisor.builder(chatMemory).build();
                      spec.advisors(memoryAdvisor);
                      spec.param(ChatMemory.CONVERSATION_ID, sessionId);
                    }

                    // Add governance advisor (owns tool calling loop)
                    spec.advisors(governanceAdvisor);
                  });

      // Execute
      String content = promptSpec.call().content();

      // Collect evidences (including any new ones from this continuation)
      List<Evidence> allEvidences = new ArrayList<>(governanceAdvisor.getEvidences());

      // Check if suspended again
      var suspensionState = governanceAdvisor.getSuspensionState();
      if (suspensionState != null) {
        return suspendForApproval(suspensionState, allEvidences, definition, context);
      }

      // Completed
      return new AgentResult(content, allEvidences);

    } finally {
      governanceAdvisor.clearState();
    }
  }

  private String executeTool(String toolName, String toolArguments) {
    ToolCallback tool =
        tools.stream()
            .filter(t -> t.getToolDefinition().name().equals(toolName))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("Tool not found: " + toolName));

    return tool.call(toolArguments);
  }

  private String buildSystemInstruction(AgentDefinition definition) {
    var name = definition.name();
    var description = definition.description();

    if (description == null || description.isBlank()) {
      return String.format("You are %s.", name);
    } else {
      return String.format("You are %s. %s", name, description);
    }
  }
}
