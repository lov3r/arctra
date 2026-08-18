package cn.bitcss.arctra.runtime.react;

import cn.bitcss.arctra.agent.AgentDefinition;
import cn.bitcss.arctra.agent.AgentExecutionContext;
import cn.bitcss.arctra.agent.AgentRequest;
import cn.bitcss.arctra.agent.AgentResult;
import cn.bitcss.arctra.evidence.Evidence;
import cn.bitcss.arctra.runtime.AgentExecutionEngine;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;

/**
 * Agent execution engine based on Spring AI Tool Calling Loop.
 *
 * <p>This engine delegates to Spring AI ChatClient with ToolCallingAdvisor for the complete tool
 * calling loop (Think → Act → Observe). Evidence is captured via per-execution ToolCallback
 * wrappers.
 *
 * <p><strong>M2 Evolution:</strong> Supports multi-turn conversation continuity via {@link
 * AgentExecutionContext}. When {@code context.sessionId()} is present, uses Spring AI {@link
 * MessageChatMemoryAdvisor} to inject conversation history and persist new messages.
 *
 * <p>Thread safety: Engine does not hold per-execution mutable state. Evidence collection is
 * execution-isolated. ChatMemory is shared across executions (same conversationId sees same
 * history). Overall concurrency safety depends on the injected ChatModel and ChatMemory
 * implementations.
 *
 * @author lov3r
 */
public class SpringAiToolCallingEngine implements AgentExecutionEngine {

  private final ChatModel chatModel;
  private final List<ToolCallback> tools;
  private final ChatMemory chatMemory;

  /**
   * Create a tool-calling engine with conversation memory support.
   *
   * @param chatModel the chat model for agent execution
   * @param tools the tools available to the agent
   * @param chatMemory the chat memory for conversation history (shared across executions)
   */
  public SpringAiToolCallingEngine(
      ChatModel chatModel, List<ToolCallback> tools, ChatMemory chatMemory) {
    this.chatModel = Objects.requireNonNull(chatModel, "chatModel cannot be null");
    this.tools = Objects.requireNonNull(tools, "tools cannot be null");
    this.chatMemory = Objects.requireNonNull(chatMemory, "chatMemory cannot be null");
  }

  @Override
  public AgentResult execute(
      AgentDefinition definition, AgentRequest request, AgentExecutionContext context) {
    // Wrap tools with evidence capture (per-execution isolation)
    List<Evidence> evidences = new ArrayList<>();
    var wrappedTools =
        tools.stream().map(tool -> new EvidenceCapturingToolCallback(tool, evidences)).toList();

    // Build ChatClient with advisors
    var clientBuilder = ChatClient.builder(chatModel);

    // Add memory advisor when session is present
    String sessionId = context.sessionId();
    if (sessionId != null) {
      var memoryAdvisor = MessageChatMemoryAdvisor.builder(chatMemory).build();
      clientBuilder.defaultAdvisors(memoryAdvisor);
    }

    var chatClient = clientBuilder.build();

    // Construct system prompt from AgentDefinition
    var systemInstruction = buildSystemInstruction(definition);

    // Build prompt
    var promptSpec =
        chatClient
            .prompt()
            .system(systemInstruction)
            .user(request.userMessage())
            .tools(wrappedTools.toArray(new ToolCallback[0]));

    // Pass sessionId to memory advisor via advisor context
    if (sessionId != null) {
      promptSpec = promptSpec.advisors(spec -> spec.param("conversationId", sessionId));
    }

    // Execute
    var content = promptSpec.call().content();

    return new AgentResult(content, evidences);
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
