package cn.bitcss.arctra.runtime.react;

import static org.assertj.core.api.Assertions.*;

import cn.bitcss.arctra.agent.AgentDefinition;
import cn.bitcss.arctra.agent.AgentExecutionContext;
import cn.bitcss.arctra.agent.AgentRequest;
import cn.bitcss.arctra.agent.AgentResult;
import cn.bitcss.arctra.governance.GovernanceDecision;
import cn.bitcss.arctra.governance.ToolGovernancePolicy;
import cn.bitcss.arctra.process.AgentProcess;
import cn.bitcss.arctra.process.ContinuationSignal.ApprovalSignal;
import cn.bitcss.arctra.runtime.AgentRuntime;
import cn.bitcss.arctra.runtime.DefaultAgentRuntime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * M4-T3 STEP 3.5-A: Message Flow Observation Test.
 *
 * <p>This test triggers the actual approval + resume flow to observe DEBUG output.
 */
@DisplayName("Message Flow Observation")
class MessageFlowObservationTest {

  @Test
  @DisplayName("Observe message flow on approval + resume")
  void observeMessageFlowOnApprovalResume() {
    System.out.println("\n" + "=".repeat(80));
    System.out.println("MESSAGE FLOW OBSERVATION TEST - START");
    System.out.println("=".repeat(80));

    AtomicInteger toolExecutionCount = new AtomicInteger(0);

    // Governance: REQUIRE_APPROVAL
    ToolGovernancePolicy policy = (toolName, args, ctx) -> {
      System.out.println("\n[Test] Governance: REQUIRE_APPROVAL for tool: " + toolName);
      return GovernanceDecision.REQUIRE_APPROVAL;
    };

    // Simple ChatModel that returns tool call on first invocation
    ChatModel chatModel = new ChatModel() {
      private int callCount = 0;

      @Override
      public ChatResponse call(Prompt prompt) {
        callCount++;
        System.out.println("\n[Test] ChatModel.call() invocation #" + callCount);

        // CAPTURE: Actual messages received by model
        System.out.println("\n=== [ACTUAL MODEL MESSAGES] Call #" + callCount + " ===");
        List<org.springframework.ai.chat.messages.Message> messages = prompt.getInstructions();
        System.out.println("  Total messages received by model: " + messages.size());
        for (int i = 0; i < messages.size(); i++) {
          var msg = messages.get(i);
          System.out.println("    [" + i + "] " + msg.getClass().getSimpleName());
        }

        boolean hasToolResponse = prompt.getInstructions().stream()
            .anyMatch(m -> m.getClass().getSimpleName().equals("ToolResponseMessage"));

        if (!hasToolResponse) {
          System.out.println("[Test] Returning ToolCall");
          return new ChatResponse(List.of(new Generation(
              AssistantMessage.builder()
                  .toolCalls(List.of(new AssistantMessage.ToolCall(
                      "call_investigate", "function", "investigate", "{}")))
                  .build())));
        } else {
          System.out.println("[Test] Returning final answer");
          return new ChatResponse(List.of(new Generation(
              new AssistantMessage("Done"))));
        }
      }

      @Override
      public org.springframework.ai.chat.prompt.ChatOptions getOptions() {
        // Return ToolCallingChatOptions so Spring AI can inject tools
        return org.springframework.ai.model.tool.ToolCallingChatOptions.builder().build();
      }
    };

    // Tool - using ToolCallback interface
    ToolCallback tool = new ToolCallback() {
      @Override
      public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
            .name("investigate")
            .description("Investigate")
            .inputSchema("{\"type\":\"object\",\"properties\":{}}")
            .build();
      }

      @Override
      public String call(String args) {
        int count = toolExecutionCount.incrementAndGet();
        System.out.println("\n[Test] Tool executed #" + count);
        return "result-" + count;
      }
    };

    // Engine
    SpringAiToolCallingEngine engine = new SpringAiToolCallingEngine(
        chatModel,
        List.of(tool),
        MessageWindowChatMemory.builder().build(),
        policy);

    AgentRuntime runtime = new DefaultAgentRuntime(engine);
    var agent = runtime.agent(new AgentDefinition("test-agent", "You are a test agent."));

    // Execute → Suspension
    System.out.println("\n[Test] ===== INITIAL EXECUTION =====");
    AgentResult result1 = agent.execute(
        new AgentRequest("test"),
        AgentExecutionContext.stateless());

    System.out.println("\n[Test] Result 1 - Suspended: " + result1.isSuspended());
    System.out.println("[Test] Tool execution count: " + toolExecutionCount.get());

    assertThat(result1.isSuspended()).isTrue();
    assertThat(toolExecutionCount.get()).isEqualTo(0);

    // Resume → Completion
    System.out.println("\n[Test] ===== RESUME APPROVED =====");
    AgentProcess process = result1.process();
    AgentResult result2 = process.resume(new ApprovalSignal(true, "approved"));

    System.out.println("\n[Test] Result 2 - Completed: " + result2.isCompleted());
    System.out.println("[Test] Tool execution count: " + toolExecutionCount.get());

    assertThat(result2.isCompleted()).isTrue();
    assertThat(toolExecutionCount.get()).isEqualTo(1);

    System.out.println("\n" + "=".repeat(80));
    System.out.println("MESSAGE FLOW OBSERVATION TEST - COMPLETE");
    System.out.println("=".repeat(80));
  }
}
