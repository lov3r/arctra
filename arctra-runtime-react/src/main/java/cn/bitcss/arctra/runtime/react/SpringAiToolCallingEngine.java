package cn.bitcss.arctra.runtime.react;

import cn.bitcss.arctra.agent.AgentDefinition;
import cn.bitcss.arctra.agent.AgentRequest;
import cn.bitcss.arctra.agent.AgentResult;
import cn.bitcss.arctra.evidence.Evidence;
import cn.bitcss.arctra.runtime.AgentExecutionEngine;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;

/**
 * Agent execution engine based on Spring AI Tool Calling Loop.
 *
 * <p>This engine delegates to Spring AI ChatClient with ToolCallingAdvisor for the complete tool
 * calling loop (Think → Act → Observe). Evidence is captured via per-execution ToolCallback
 * wrappers.
 *
 * <p>Thread safety: Engine does not hold per-execution mutable state. Evidence collection is
 * execution-isolated. Overall concurrency safety depends on the injected ChatModel and
 * ToolCallbacks.
 *
 * @author lov3r
 */
public class SpringAiToolCallingEngine implements AgentExecutionEngine {

  private final ChatClient chatClient;
  private final List<ToolCallback> tools;

  /**
   * Creates a new engine with the given ChatModel and tools.
   *
   * @param chatModel the Spring AI ChatModel to use
   * @param tools the available tools for this engine (immutable after construction)
   */
  public SpringAiToolCallingEngine(ChatModel chatModel, List<ToolCallback> tools) {
    Objects.requireNonNull(chatModel, "chatModel cannot be null");
    Objects.requireNonNull(tools, "tools cannot be null");

    this.chatClient = ChatClient.builder(chatModel).build();
    this.tools = List.copyOf(tools); // defensive copy, immutable
  }

  @Override
  public AgentResult execute(AgentDefinition definition, AgentRequest request) {
    Objects.requireNonNull(definition, "definition cannot be null");
    Objects.requireNonNull(request, "request cannot be null");

    // Per-execution evidence collection
    List<Evidence> evidences = new ArrayList<>();

    // Wrap tools with evidence capture (per-execution)
    var wrappedTools =
        tools.stream().map(tool -> new EvidenceCapturingToolCallback(tool, evidences)).toList();

    // Construct system prompt from AgentDefinition
    var systemInstruction = buildSystemInstruction(definition);

    // Execute via ChatClient with per-prompt tools
    var content =
        chatClient
            .prompt()
            .system(systemInstruction)
            .user(request.userMessage())
            .tools((Object) wrappedTools.toArray(new ToolCallback[0]))
            .call()
            .content();

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
