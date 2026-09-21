package cn.bitcss.arctra.runtime.react;

import static org.assertj.core.api.Assertions.assertThat;

import cn.bitcss.arctra.agent.Agent;
import cn.bitcss.arctra.agent.AgentDefinition;
import cn.bitcss.arctra.agent.AgentExecutionContext;
import cn.bitcss.arctra.agent.AgentRequest;
import cn.bitcss.arctra.agent.AgentResult;
import cn.bitcss.arctra.governance.GovernanceDecision;
import cn.bitcss.arctra.governance.ToolGovernancePolicy;
import cn.bitcss.arctra.runtime.AgentRuntime;
import cn.bitcss.arctra.runtime.DefaultAgentRuntime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * BLOCKER 1 runtime test: verify that ToolCallingChatOptions are preserved
 * across multiple tool call iterations, allowing the model to call different
 * tools in sequence.
 *
 * Scenario:
 * - Model call #1 → ToolCall A (ALLOW) → execute
 * - Model call #2 → ToolCall B (ALLOW) → execute
 * - Model call #3 → final answer
 *
 * Assert:
 * - Tool A executed exactly once
 * - Tool B executed exactly once
 * - Final answer received
 * - Prompt options preserved across all 3 calls
 */
public class MultiToolSequenceTest {

  @Test
  void multiToolSequence_toolOptionsPreservedAcrossIterations() {
    // Track tool executions
    AtomicInteger toolACount = new AtomicInteger(0);
    AtomicInteger toolBCount = new AtomicInteger(0);

    // Track model calls and their available tools
    List<List<Message>> capturedModelCalls = new ArrayList<>();
    List<Boolean> hadToolOptions = new ArrayList<>();

    // ChatModel that returns sequential tool calls
    ChatModel chatModel = new ChatModel() {
      private int callCount = 0;

      @Override
      public ChatResponse call(Prompt prompt) {
        callCount++;
        System.out.println("\n[Test] Model call #" + callCount);

        // Capture messages
        capturedModelCalls.add(new ArrayList<>(prompt.getInstructions()));

        // Check if prompt has tool options
        boolean hasOptions = prompt.getOptions() != null;
        hadToolOptions.add(hasOptions);
        System.out.println("[Test] Prompt has options: " + hasOptions);
        System.out.println("[Test] Options type: " + (prompt.getOptions() != null ? prompt.getOptions().getClass().getSimpleName() : "null"));

        if (callCount == 1) {
          // First call: return toolA call
          return new ChatResponse(List.of(new Generation(
              AssistantMessage.builder()
                  .content("Calling tool A")
                  .toolCalls(List.of(new AssistantMessage.ToolCall(
                      "call_a", "function", "toolA", "{}")))
                  .build())));
        } else if (callCount == 2) {
          // Second call: return toolB call
          return new ChatResponse(List.of(new Generation(
              AssistantMessage.builder()
                  .content("Calling tool B")
                  .toolCalls(List.of(new AssistantMessage.ToolCall(
                      "call_b", "function", "toolB", "{}")))
                  .build())));
        } else {
          // Third call: final answer
          return new ChatResponse(List.of(new Generation(
              new AssistantMessage("Both tools executed successfully"))));
        }
      }

      @Override
      public ChatOptions getOptions() {
        // CRITICAL: Return a ToolCallingChatOptions.Builder so Spring AI can inject tools
        return org.springframework.ai.model.tool.ToolCallingChatOptions.builder().build();
      }
    };

    // Tool A
    ToolCallback toolA = new ToolCallback() {
      @Override
      public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
            .name("toolA")
            .description("Tool A")
            .inputSchema("{\"type\":\"object\",\"properties\":{}}")
            .build();
      }

      @Override
      public String call(String arguments) {
        toolACount.incrementAndGet();
        System.out.println("[Test] Tool A executed");
        return "Tool A result";
      }
    };

    // Tool B
    ToolCallback toolB = new ToolCallback() {
      @Override
      public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
            .name("toolB")
            .description("Tool B")
            .inputSchema("{\"type\":\"object\",\"properties\":{}}")
            .build();
      }

      @Override
      public String call(String arguments) {
        toolBCount.incrementAndGet();
        System.out.println("[Test] Tool B executed");
        return "Tool B result";
      }
    };

    List<ToolCallback> tools = List.of(toolA, toolB);

    // Governance: ALLOW all
    ToolGovernancePolicy policy = (toolName, args, ctx) -> GovernanceDecision.ALLOW;

    // Setup
    ChatMemory chatMemory = MessageWindowChatMemory.builder().maxMessages(100).build();
    var engine = new SpringAiToolCallingEngine(chatModel, tools, chatMemory, policy);
    AgentRuntime runtime = new DefaultAgentRuntime(engine);
    var definition = new AgentDefinition("test", "Test agent");
    var agent = runtime.agent(definition);

    // Execute (no session needed for this test)
    AgentExecutionContext context = AgentExecutionContext.withSession("test-session");
    AgentResult result = agent.execute(new AgentRequest("Execute both tools"), context);

    // Assertions
    System.out.println("\n=== ASSERTIONS ===");
    System.out.println("Tool A count: " + toolACount.get());
    System.out.println("Tool B count: " + toolBCount.get());
    System.out.println("Model calls: " + capturedModelCalls.size());
    System.out.println("Result completed: " + result.isCompleted());
    System.out.println("Result content: " + result.content());

    // BLOCKER 1: Verify tools were preserved and both executed
    assertThat(toolACount.get()).isEqualTo(1);
    assertThat(toolBCount.get()).isEqualTo(1);
    assertThat(capturedModelCalls).hasSize(3);
    assertThat(result.isCompleted()).isTrue();
    assertThat(result.content()).contains("Both tools executed successfully");

    // Verify all model calls had tool options
    System.out.println("\n=== TOOL OPTIONS CHECK ===");
    for (int i = 0; i < hadToolOptions.size(); i++) {
      System.out.println("Model call #" + (i + 1) + " had options: " + hadToolOptions.get(i));
    }

    // All calls should have options to preserve tool definitions
    assertThat(hadToolOptions).allMatch(had -> had, "All model calls should have tool options");
  }
}
