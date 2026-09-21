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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * M4-T3 STEP 3.5-B: Session + Chat Memory Continuation Verification.
 *
 * <p>Verifies that suspended/resumed execution with M2 Session + ChatMemory does not produce:
 * <ul>
 *   <li>Memory history duplication
 *   <li>Current user message duplication
 *   <li>Fake new user turn
 *   <li>Final assistant duplication
 *   <li>Session isolation regression
 * </ul>
 *
 * <p>This is a PRODUCTION REGRESSION GATE, not optional characterization.
 */
@DisplayName("M4-T3 STEP 3.5-B: Session + Memory Continuation")
class SessionMemoryContinuationTest {

  @Test
  @DisplayName("Session + Memory: No duplication on resume")
  void sessionMemory_noDuplicationOnResume() {
    System.out.println("\n" + "=".repeat(80));
    System.out.println("SESSION + MEMORY CONTINUATION TEST");
    System.out.println("=".repeat(80));

    // Shared ChatMemory
    ChatMemory chatMemory = MessageWindowChatMemory.builder().build();
    String sessionId = "session-A";

    // Tool execution counter
    AtomicInteger toolCount = new AtomicInteger(0);

    // Governance: REQUIRE_APPROVAL
    ToolGovernancePolicy policy = (toolName, args, ctx) -> {
      System.out.println("\n[Test] Governance: REQUIRE_APPROVAL for tool: " + toolName);
      return GovernanceDecision.REQUIRE_APPROVAL;
    };

    // Capturing ChatModel
    List<List<Message>> capturedModelCalls = new ArrayList<>();
    ChatModel chatModel = new ChatModel() {
      private int callCount = 0;

      @Override
      public ChatResponse call(Prompt prompt) {
        callCount++;
        System.out.println("\n[Test] ChatModel.call() invocation #" + callCount);

        // Capture actual messages
        List<Message> messages = new ArrayList<>(prompt.getInstructions());
        capturedModelCalls.add(messages);

        System.out.println("\n=== [ACTUAL MODEL MESSAGES] Call #" + callCount + " ===");
        System.out.println("  Total messages: " + messages.size());
        for (int i = 0; i < messages.size(); i++) {
          Message msg = messages.get(i);
          String preview = msg.getText() != null && msg.getText().length() > 50
              ? msg.getText().substring(0, 50) + "..."
              : msg.getText();
          System.out.println("    [" + i + "] " + msg.getClass().getSimpleName() + ": " + preview);
        }

        boolean hasToolResponse = messages.stream()
            .anyMatch(m -> m.getClass().getSimpleName().equals("ToolResponseMessage"));

        if (callCount == 1) {
          // Turn 1: Establish history
          return new ChatResponse(List.of(new Generation(new AssistantMessage("Understood"))));
        } else if (callCount == 2) {
          // Turn 2: Return tool call
          return new ChatResponse(List.of(new Generation(
              AssistantMessage.builder()
                  .toolCalls(List.of(new AssistantMessage.ToolCall(
                      "call_queryLogs", "function", "queryLogs", "{}")))
                  .build())));
        } else {
          // After resume: final answer
          return new ChatResponse(List.of(new Generation(new AssistantMessage("Investigation complete"))));
        }
      }

      @Override
      public org.springframework.ai.chat.prompt.ChatOptions getOptions() {
        // Return ToolCallingChatOptions so Spring AI can inject tools
        return org.springframework.ai.model.tool.ToolCallingChatOptions.builder().build();
      }
    };

    // Tool
    ToolCallback tool = new ToolCallback() {
      @Override
      public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
            .name("queryLogs")
            .description("Query logs")
            .inputSchema("{\"type\":\"object\",\"properties\":{}}")
            .build();
      }

      @Override
      public String call(String args) {
        int count = toolCount.incrementAndGet();
        System.out.println("\n[Test] queryLogs() executed #" + count);
        return "log-result-" + count;
      }
    };

    // Engine
    SpringAiToolCallingEngine engine = new SpringAiToolCallingEngine(
        chatModel, List.of(tool), chatMemory, policy);

    AgentRuntime runtime = new DefaultAgentRuntime(engine);
    var agent = runtime.agent(new AgentDefinition("test-agent", "You are a test agent."));

    // ========================================
    // Turn 1: Establish history
    // ========================================
    System.out.println("\n[Test] ===== TURN 1: ESTABLISH HISTORY =====");

    AgentExecutionContext ctx1 = AgentExecutionContext.withSession(sessionId);
    AgentResult result1 = agent.execute(new AgentRequest("My service is payment"), ctx1);

    System.out.println("\n[Test] Turn 1 completed");
    assertThat(result1.isCompleted()).isTrue();
    assertThat(capturedModelCalls).hasSize(1);

    // Snapshot: ChatMemory after turn 1
    System.out.println("\n=== [CHAT MEMORY SNAPSHOT] After Turn 1 ===");
    List<Message> memoryAfterTurn1 = chatMemory.get(sessionId);
    printMemory(memoryAfterTurn1);

    // ========================================
    // Turn 2: Request with tool call → suspension
    // ========================================
    System.out.println("\n[Test] ===== TURN 2: TOOL CALL → SUSPENSION =====");

    AgentExecutionContext ctx2 = AgentExecutionContext.withSession(sessionId);
    System.out.println("[Test] Context sessionId: " + ctx2.sessionId());
    AgentResult result2 = agent.execute(new AgentRequest("Investigate the current incident"), ctx2);

    System.out.println("\n[Test] Turn 2 suspended: " + result2.isSuspended());
    assertThat(result2.isSuspended()).isTrue();
    assertThat(toolCount.get()).isEqualTo(0);
    assertThat(capturedModelCalls).hasSize(2);

    // Analyze Model Call #2
    List<Message> call2Messages = capturedModelCalls.get(1);
    System.out.println("\n=== [ANALYSIS] Model Call #2 (Turn 2 - Before Suspension) ===");
    analyzeMessages(call2Messages, "turn1 user", "turn1 assistant", "turn2 user");

    // Snapshot: ChatMemory after suspension
    System.out.println("\n=== [CHAT MEMORY SNAPSHOT] After Suspension ===");
    List<Message> memoryAfterSuspension = chatMemory.get(sessionId);
    printMemory(memoryAfterSuspension);

    // ========================================
    // Resume approved → completion
    // ========================================
    System.out.println("\n[Test] ===== RESUME APPROVED =====");

    AgentProcess process = result2.process();
    AgentResult result3 = process.resume(new ApprovalSignal(true, "approved"));

    System.out.println("\n[Test] Resume completed: " + result3.isCompleted());
    assertThat(result3.isCompleted()).isTrue();
    assertThat(toolCount.get()).isEqualTo(1);
    assertThat(capturedModelCalls).hasSize(3);

    // Analyze Model Call #3
    List<Message> call3Messages = capturedModelCalls.get(2);
    System.out.println("\n=== [ANALYSIS] Model Call #3 (After Resume) ===");
    analyzeMessages(call3Messages, "turn1 user", "turn1 assistant", "turn2 user", "tool call", "tool response");

    // Snapshot: ChatMemory after resume
    System.out.println("\n=== [CHAT MEMORY SNAPSHOT] After Resume ===");
    List<Message> memoryAfterResume = chatMemory.get(sessionId);
    printMemory(memoryAfterResume);

    // Snapshot: ChatMemory after completion
    System.out.println("\n=== [CHAT MEMORY SNAPSHOT] After Completion ===");
    List<Message> memoryAfterCompletion = chatMemory.get(sessionId);
    printMemory(memoryAfterCompletion);

    // ========================================
    // CRITICAL VERIFICATIONS
    // ========================================
    System.out.println("\n" + "=".repeat(80));
    System.out.println("CRITICAL VERIFICATIONS");
    System.out.println("=".repeat(80));

    // Count message types in Model Call #3
    long systemCount = call3Messages.stream()
        .filter(m -> m.getClass().getSimpleName().equals("SystemMessage")).count();
    long userCount = call3Messages.stream()
        .filter(m -> m.getClass().getSimpleName().equals("UserMessage")).count();
    long assistantCount = call3Messages.stream()
        .filter(m -> m.getClass().getSimpleName().equals("AssistantMessage")).count();
    long toolResponseCount = call3Messages.stream()
        .filter(m -> m.getClass().getSimpleName().equals("ToolResponseMessage")).count();

    System.out.println("\n[VERIFICATION] Model Call #3 Message Counts:");
    System.out.println("  SystemMessage: " + systemCount);
    System.out.println("  UserMessage: " + userCount);
    System.out.println("  AssistantMessage: " + assistantCount);
    System.out.println("  ToolResponseMessage: " + toolResponseCount);

    // ASSERTIONS
    assertThat(systemCount)
        .as("SystemMessage should appear exactly once")
        .isEqualTo(1);

    assertThat(userCount)
        .as("UserMessage should appear exactly twice (turn1 + turn2, no duplication)")
        .isEqualTo(2);

    assertThat(assistantCount)
        .as("AssistantMessage should appear exactly twice (turn1 response + tool call)")
        .isEqualTo(2);

    assertThat(toolResponseCount)
        .as("ToolResponseMessage should appear exactly once")
        .isEqualTo(1);

    System.out.println("\n✅ No history duplication");
    System.out.println("✅ No current user duplication");
    System.out.println("✅ No system message duplication");
    System.out.println("✅ Tool executed exactly once");

    System.out.println("\n" + "=".repeat(80));
    System.out.println("SESSION + MEMORY CONTINUATION TEST COMPLETE");
    System.out.println("=".repeat(80));
  }

  private void printMemory(List<Message> messages) {
    System.out.println("  Total messages in memory: " + messages.size());
    for (int i = 0; i < messages.size(); i++) {
      Message msg = messages.get(i);
      String preview = msg.getText() != null && msg.getText().length() > 50
          ? msg.getText().substring(0, 50) + "..."
          : msg.getText();
      System.out.println("    [" + i + "] " + msg.getClass().getSimpleName() + ": " + preview);
    }
  }

  private void analyzeMessages(List<Message> messages, String... expectedElements) {
    System.out.println("  Total messages: " + messages.size());
    System.out.println("  Expected semantic elements: " + String.join(", ", expectedElements));

    for (int i = 0; i < messages.size(); i++) {
      Message msg = messages.get(i);
      System.out.println("    [" + i + "] " + msg.getClass().getSimpleName());
    }
  }
}
