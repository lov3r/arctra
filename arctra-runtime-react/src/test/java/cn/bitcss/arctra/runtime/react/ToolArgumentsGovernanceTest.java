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
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * BLOCKER 3 Test: Tool arguments should be passed to governance policy
 */
class ToolArgumentsGovernanceTest {

  @Test
  void governance_shouldReceiveToolArguments() {
    System.out.println("\n" + "=".repeat(80));
    System.out.println("BLOCKER 3: TOOL ARGUMENTS GOVERNANCE TEST");
    System.out.println("=".repeat(80));

    // Capture governance calls
    List<String> capturedToolNames = new ArrayList<>();
    List<String> capturedArguments = new ArrayList<>();

    AtomicInteger toolExecutionCount = new AtomicInteger(0);

    // ChatModel that returns a tool call with arguments
    ChatModel chatModel = new ChatModel() {
      private int callCount = 0;

      @Override
      public ChatResponse call(Prompt prompt) {
        callCount++;
        System.out.println("\n[Test] Model call #" + callCount);

        boolean hasToolResponse = prompt.getInstructions().stream()
            .anyMatch(m -> m instanceof org.springframework.ai.chat.messages.ToolResponseMessage);

        if (!hasToolResponse) {
          // First call: return tool call WITH ARGUMENTS
          String toolArgs = "{\"userId\":\"user123\",\"action\":\"delete\"}";
          System.out.println("[Test] Returning tool call with args: " + toolArgs);
          return new ChatResponse(List.of(new Generation(
              AssistantMessage.builder()
                  .content("Let me delete the user")
                  .toolCalls(List.of(new AssistantMessage.ToolCall(
                      "call_delete", "function", "deleteUser", toolArgs)))
                  .build())));
        } else {
          // After tool execution: final answer
          return new ChatResponse(List.of(new Generation(
              new AssistantMessage("User deleted successfully"))));
        }
      }

      @Override
      public ChatOptions getOptions() {
        return org.springframework.ai.model.tool.ToolCallingChatOptions.builder().build();
      }
    };

    // Tool
    ToolCallback tool = new ToolCallback() {
      @Override
      public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
            .name("deleteUser")
            .description("Delete a user account")
            .inputSchema("{\"type\":\"object\",\"properties\":{\"userId\":{\"type\":\"string\"},\"action\":{\"type\":\"string\"}}}")
            .build();
      }

      @Override
      public String call(String arguments) {
        int count = toolExecutionCount.incrementAndGet();
        System.out.println("[Test] Tool 'deleteUser' executed #" + count + " with args: " + arguments);
        return "User deleted";
      }
    };

    // Governance: Capture arguments and require approval for delete actions
    ToolGovernancePolicy policy = (toolName, args, ctx) -> {
      System.out.println("\n[Test] Governance called:");
      System.out.println("  Tool: " + toolName);
      System.out.println("  Arguments: " + args);

      capturedToolNames.add(toolName);
      capturedArguments.add(args != null ? args : "NULL");

      // Check if this is a delete action
      if (args != null && args.contains("\"action\":\"delete\"")) {
        System.out.println("  Decision: REQUIRE_APPROVAL (delete action detected)");
        return GovernanceDecision.REQUIRE_APPROVAL;
      }

      System.out.println("  Decision: ALLOW");
      return GovernanceDecision.ALLOW;
    };

    // Setup
    ChatMemory chatMemory = MessageWindowChatMemory.builder().maxMessages(100).build();
    var engine = new SpringAiToolCallingEngine(chatModel, List.of(tool), chatMemory, policy);
    AgentRuntime runtime = new DefaultAgentRuntime(engine);
    var definition = new AgentDefinition("test", "Test agent");
    var agent = runtime.agent(definition);

    // ========================================
    // Execute - should SUSPEND due to delete action
    // ========================================
    System.out.println("\n=== EXECUTION ===");
    AgentExecutionContext context = AgentExecutionContext.withSession("test-session");
    AgentResult result = agent.execute(new AgentRequest("Delete user user123"), context);

    System.out.println("\n=== RESULTS ===");
    System.out.println("Is suspended: " + result.isSuspended());

    // ========================================
    // BLOCKER 3 ASSERTIONS
    // ========================================
    System.out.println("\n=== BLOCKER 3 ASSERTIONS ===");

    // Governance should have been called
    assertThat(capturedToolNames).isNotEmpty();
    System.out.println("✓ Governance was called");

    // CRITICAL: Arguments should be passed to governance
    assertThat(capturedArguments).isNotEmpty();
    System.out.println("✓ Arguments captured: " + capturedArguments);

    assertThat(capturedArguments.get(0)).isNotNull();
    assertThat(capturedArguments.get(0)).isNotEqualTo("NULL");
    System.out.println("✓ Arguments not null");

    // Arguments should contain the actual JSON
    assertThat(capturedArguments.get(0)).contains("userId");
    assertThat(capturedArguments.get(0)).contains("user123");
    assertThat(capturedArguments.get(0)).contains("delete");
    System.out.println("✓ Arguments contain expected values");

    // Result should be suspended because governance detected delete action
    assertThat(result.isSuspended()).isTrue();
    System.out.println("✓ Result suspended based on argument inspection");

    System.out.println("\n" + "=".repeat(80));
    System.out.println("BLOCKER 3 TEST PASSED");
    System.out.println("=".repeat(80));
  }
}
