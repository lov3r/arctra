package cn.bitcss.arctra.runtime.react;

import static org.assertj.core.api.Assertions.assertThat;

import cn.bitcss.arctra.agent.Agent;
import cn.bitcss.arctra.agent.AgentDefinition;
import cn.bitcss.arctra.agent.AgentExecutionContext;
import cn.bitcss.arctra.agent.AgentRequest;
import cn.bitcss.arctra.agent.AgentResult;
import cn.bitcss.arctra.evidence.Evidence;
import cn.bitcss.arctra.governance.GovernanceDecision;
import cn.bitcss.arctra.governance.ToolGovernancePolicy;
import cn.bitcss.arctra.process.ContinuationSignal.ApprovalSignal;
import cn.bitcss.arctra.runtime.AgentRuntime;
import cn.bitcss.arctra.runtime.DefaultAgentRuntime;
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
 * BLOCKER 2 Test: Evidence should be collected after tool execution on resume
 */
class EvidenceCollectionTest {

  @Test
  void evidenceCollection_afterApprovalAndResume() {
    System.out.println("\n" + "=".repeat(80));
    System.out.println("BLOCKER 2: EVIDENCE COLLECTION TEST");
    System.out.println("=".repeat(80));

    AtomicInteger toolExecutionCount = new AtomicInteger(0);

    // ChatModel that returns a tool call, then final answer
    ChatModel chatModel = new ChatModel() {
      private int callCount = 0;

      @Override
      public ChatResponse call(Prompt prompt) {
        callCount++;
        System.out.println("\n[Test] Model call #" + callCount);

        boolean hasToolResponse = prompt.getInstructions().stream()
            .anyMatch(m -> m instanceof org.springframework.ai.chat.messages.ToolResponseMessage);

        if (!hasToolResponse) {
          // First call: return tool call
          return new ChatResponse(List.of(new Generation(
              AssistantMessage.builder()
                  .content("Let me investigate")
                  .toolCalls(List.of(new AssistantMessage.ToolCall(
                      "call_investigate", "function", "investigate", "{}")))
                  .build())));
        } else {
          // After tool execution: final answer
          return new ChatResponse(List.of(new Generation(
              new AssistantMessage("Investigation complete based on tool result"))));
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
            .name("investigate")
            .description("Investigate the issue")
            .inputSchema("{\"type\":\"object\",\"properties\":{}}")
            .build();
      }

      @Override
      public String call(String arguments) {
        int count = toolExecutionCount.incrementAndGet();
        System.out.println("[Test] Tool 'investigate' executed #" + count);
        return "Investigation result: issue found in payment service";
      }
    };

    // Governance: REQUIRE_APPROVAL
    ToolGovernancePolicy policy = (toolName, args, ctx) -> {
      System.out.println("[Test] Governance: REQUIRE_APPROVAL for tool: " + toolName);
      return GovernanceDecision.REQUIRE_APPROVAL;
    };

    // Setup
    ChatMemory chatMemory = MessageWindowChatMemory.builder().maxMessages(100).build();
    var engine = new SpringAiToolCallingEngine(chatModel, List.of(tool), chatMemory, policy);
    AgentRuntime runtime = new DefaultAgentRuntime(engine);
    var definition = new AgentDefinition("test", "Test agent");
    var agent = runtime.agent(definition);

    // ========================================
    // Phase 1: Execute - should SUSPEND
    // ========================================
    System.out.println("\n=== PHASE 1: EXECUTE (EXPECT SUSPENSION) ===");
    AgentExecutionContext context = AgentExecutionContext.withSession("test-session");
    AgentResult result1 = agent.execute(new AgentRequest("Investigate the issue"), context);

    System.out.println("\n=== PHASE 1 RESULTS ===");
    System.out.println("Is suspended: " + result1.isSuspended());
    System.out.println("Process ID: " + result1.process().id());
    System.out.println("Tool execution count: " + toolExecutionCount.get());

    // Assertions for Phase 1
    assertThat(result1.isSuspended()).isTrue();
    assertThat(result1.process()).isNotNull();
    assertThat(toolExecutionCount.get()).isEqualTo(0); // Tool not executed yet

    // ========================================
    // Phase 2: Resume - should execute tool and complete
    // ========================================
    System.out.println("\n=== PHASE 2: RESUME (EXPECT COMPLETION) ===");
    AgentResult result2 = result1.process().resume(
        new ApprovalSignal(true, "Approved by user"));

    System.out.println("\n=== PHASE 2 RESULTS ===");
    System.out.println("Is completed: " + result2.isCompleted());
    System.out.println("Content: " + result2.content());
    System.out.println("Tool execution count: " + toolExecutionCount.get());

    // Extract evidences
    List<Evidence> evidences = result2.evidences();
    System.out.println("\n=== EVIDENCE CHECK ===");
    System.out.println("Evidence count: " + evidences.size());
    for (int i = 0; i < evidences.size(); i++) {
      Evidence e = evidences.get(i);
      System.out.println("Evidence[" + i + "]:");
      System.out.println("  Source: " + e.source());
      System.out.println("  Content: " + e.content());
    }

    // ========================================
    // BLOCKER 2 ASSERTIONS
    // ========================================
    System.out.println("\n=== BLOCKER 2 ASSERTIONS ===");

    // Tool should have been executed
    assertThat(toolExecutionCount.get()).isEqualTo(1);
    System.out.println("✓ Tool executed once");

    // Result should be completed
    assertThat(result2.isCompleted()).isTrue();
    System.out.println("✓ Result completed");

    // CRITICAL: Evidence should contain the tool execution result
    assertThat(evidences).isNotEmpty();
    System.out.println("✓ Evidence not empty: " + evidences.size());

    assertThat(evidences).anyMatch(e ->
        e.source().equals("tool:investigate") &&
        e.content().contains("issue found in payment service"));
    System.out.println("✓ Evidence contains tool result");

    System.out.println("\n" + "=".repeat(80));
    System.out.println("BLOCKER 2 TEST PASSED");
    System.out.println("=".repeat(80));
  }
}
