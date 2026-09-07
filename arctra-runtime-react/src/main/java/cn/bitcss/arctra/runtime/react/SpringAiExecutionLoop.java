package cn.bitcss.arctra.runtime.react;

import cn.bitcss.arctra.agent.AgentDefinition;
import cn.bitcss.arctra.agent.AgentExecutionContext;
import cn.bitcss.arctra.agent.AgentRequest;
import cn.bitcss.arctra.agent.AgentResult;
import cn.bitcss.arctra.evidence.Evidence;
import cn.bitcss.arctra.governance.GovernanceDecision;
import cn.bitcss.arctra.governance.ToolGovernancePolicy;
import cn.bitcss.arctra.process.AgentProcess;
import cn.bitcss.arctra.process.ContinuationSignal;
import cn.bitcss.arctra.process.ContinuationSignal.ApprovalSignal;
import cn.bitcss.arctra.runtime.ProcessFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;

/**
 * Arctra-controlled Spring AI tool-calling execution loop.
 *
 * <p>Package-private implementation that owns the tool-calling control plane while reusing Spring
 * AI tool/model mechanics.
 *
 * <h2>Ownership Boundary</h2>
 *
 * <p><b>Arctra owns (control plane):</b>
 *
 * <ul>
 *   <li>Tool-loop orchestration (while toolCall {...})
 *   <li>Governance interception (ALLOW/DENY/REQUIRE_APPROVAL)
 *   <li>Suspension detection
 *   <li>Process materialization
 *   <li>Protocol-level controlled re-entry
 * </ul>
 *
 * <p><b>Spring AI owns (data plane):</b>
 *
 * <ul>
 *   <li>ChatModel
 *   <li>ToolCallback mechanics
 *   <li>Tool argument parsing
 *   <li>Message protocol
 * </ul>
 *
 * <h2>M4 Scope</h2>
 *
 * <p>This is in-memory, single-JVM implementation. Process state is not serializable.
 *
 * @author lov3r
 * @since M4
 */
class SpringAiExecutionLoop {

  private final ChatModel chatModel;
  private final List<ToolCallback> tools;
  private final ToolGovernancePolicy governancePolicy;

  SpringAiExecutionLoop(
      ChatModel chatModel, List<ToolCallback> tools, ToolGovernancePolicy governancePolicy) {
    this.chatModel = chatModel;
    this.tools = tools;
    this.governancePolicy = governancePolicy;
  }

  /**
   * Execute agent with governance-aware tool-calling loop.
   *
   * @param definition agent definition
   * @param request agent request
   * @param context execution context
   * @return agent result (completed or suspended)
   */
  AgentResult execute(
      AgentDefinition definition, AgentRequest request, AgentExecutionContext context) {

    // Build initial messages
    List<Message> conversationMessages = new ArrayList<>();
    conversationMessages.add(new UserMessage(buildSystemInstruction(definition)));
    conversationMessages.add(new UserMessage(request.userMessage()));

    // Evidence collection
    List<Evidence> evidences = new ArrayList<>();

    // Execute tool-calling loop
    return executeLoop(conversationMessages, evidences, definition, context);
  }

  /**
   * Core tool-calling loop with governance.
   *
   * <p>This is where Arctra takes ownership of the loop orchestration.
   */
  private AgentResult executeLoop(
      List<Message> conversationMessages,
      List<Evidence> evidences,
      AgentDefinition definition,
      AgentExecutionContext context) {

    while (true) {
      // Call Model
      Prompt prompt = new Prompt(conversationMessages);
      ChatResponse response = chatModel.call(prompt);

      AssistantMessage assistantMessage =
          Objects.requireNonNull(response.getResult()).getOutput();

      // Check for tool calls
      if (assistantMessage.getToolCalls().isEmpty()) {
        // No tool calls → final answer
        return new AgentResult(assistantMessage.getText(), evidences);
      }

      // Tool calls detected → governance interception
      var toolCall = assistantMessage.getToolCalls().getFirst(); // M4: single tool call for now
      String toolCallId = toolCall.id();
      String toolName = toolCall.name();
      String toolArguments = toolCall.arguments();

      // GOVERNANCE EVALUATION
      GovernanceDecision decision = governancePolicy.evaluate(toolName, toolArguments, context);

      switch (decision) {
        case ALLOW -> {
          // Execute tool
          String toolResult = executeTool(toolName, toolArguments);

          // Capture evidence with tool: prefix (M1 convention)
          evidences.add(new Evidence("tool:" + toolName, toolResult));

          // Append protocol messages
          conversationMessages.add(assistantMessage);
          conversationMessages.add(
              ToolResponseMessage.builder()
                  .responses(List.of(
                      new ToolResponseMessage.ToolResponse(toolCallId, toolName, toolResult)))
                  .build());

          // Continue loop
        }

        case DENY -> {
          // Tool denied → inject denial message
          String denialMessage =
              String.format("Tool '%s' invocation denied by policy", toolName);

          conversationMessages.add(assistantMessage);
          conversationMessages.add(
              ToolResponseMessage.builder()
                  .responses(List.of(
                      new ToolResponseMessage.ToolResponse(toolCallId, toolName, denialMessage)))
                  .build());

          // Continue loop (Model sees denial)
        }

        case REQUIRE_APPROVAL -> {
          // Suspension required → materialize Process
          return materializeProcess(
              conversationMessages,
              assistantMessage,
              toolCallId,
              toolName,
              toolArguments,
              evidences,
              definition,
              context);
        }
      }
    }
  }

  /**
   * Materialize AgentProcess for suspension.
   *
   * <p>PROTOCOL-LEVEL CONTROLLED RE-ENTRY: Captures Spring AI protocol state for resumption.
   */
  private AgentResult materializeProcess(
      List<Message> conversationBeforeSuspension,
      AssistantMessage assistantMessageWithToolCall,
      String toolCallId,
      String toolName,
      String toolArguments,
      List<Evidence> evidences,
      AgentDefinition definition,
      AgentExecutionContext context) {

    // Capture suspension context
    SpringAiSuspensionContext suspensionContext =
        new SpringAiSuspensionContext(
            List.copyOf(conversationBeforeSuspension),
            assistantMessageWithToolCall,
            toolCallId,
            toolName,
            toolArguments,
            definition,
            context,
            List.copyOf(evidences));

    // Create continuation function with explicit type
    Function<ContinuationSignal, AgentResult> continuationFunction =
        signal -> {
          if (signal instanceof ApprovalSignal approval) {
            if (approval.approved()) {
              return resumeApproved(suspensionContext);
            } else {
              return resumeDenied(suspensionContext);
            }
          }
          throw new IllegalArgumentException("Unknown ContinuationSignal type: " + signal);
        };

    // Materialize Process
    AgentProcess process = ProcessFactory.createSuspended(continuationFunction);

    // Return suspended result
    String partialContent =
        String.format("Execution suspended: tool '%s' requires approval", toolName);
    return new AgentResult(partialContent, evidences, process);
  }

  /** Resume after approval: execute EXACT pending tool call. */
  private AgentResult resumeApproved(SpringAiSuspensionContext ctx) {
    // Execute pending tool
    String toolResult = executeTool(ctx.toolName(), ctx.toolArguments());

    // Capture evidence with tool: prefix (M1 convention)
    List<Evidence> updatedEvidences = new ArrayList<>(ctx.evidences());
    updatedEvidences.add(new Evidence("tool:" + ctx.toolName(), toolResult));

    // Build next conversation with protocol messages
    List<Message> nextConversation = new ArrayList<>(ctx.conversationBeforeSuspension());
    nextConversation.add(ctx.assistantMessageWithToolCall());
    nextConversation.add(
        ToolResponseMessage.builder()
            .responses(List.of(
                new ToolResponseMessage.ToolResponse(
                    ctx.toolCallId(), ctx.toolName(), toolResult)))
            .build());

    // Continue execution loop
    return executeLoop(nextConversation, updatedEvidences, ctx.definition(), ctx.context());
  }

  /** Resume after denial: tool NOT executed. */
  private AgentResult resumeDenied(SpringAiSuspensionContext ctx) {
    String denialMessage =
        String.format(
            "Tool '%s' invocation rejected by approval decision", ctx.toolName());

    // Build next conversation with denial
    List<Message> nextConversation = new ArrayList<>(ctx.conversationBeforeSuspension());
    nextConversation.add(ctx.assistantMessageWithToolCall());
    nextConversation.add(
        ToolResponseMessage.builder()
            .responses(List.of(
                new ToolResponseMessage.ToolResponse(
                    ctx.toolCallId(), ctx.toolName(), denialMessage)))
            .build());

    // Continue execution loop (Model sees rejection)
    return executeLoop(nextConversation, ctx.evidences(), ctx.definition(), ctx.context());
  }

  /** Execute tool callback. */
  private String executeTool(String toolName, String toolArguments) {
    ToolCallback tool =
        tools.stream()
            .filter(t -> t.getToolDefinition().name().equals(toolName))
            .findFirst()
            .orElseThrow(
                () -> new IllegalStateException("Tool not found: " + toolName));

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

  /**
   * Suspension context for protocol-level controlled re-entry.
   *
   * <p>Package-private record capturing Spring AI protocol state.
   */
  record SpringAiSuspensionContext(
      List<Message> conversationBeforeSuspension,
      AssistantMessage assistantMessageWithToolCall,
      String toolCallId,
      String toolName,
      String toolArguments,
      AgentDefinition definition,
      AgentExecutionContext context,
      List<Evidence> evidences) {}
}
