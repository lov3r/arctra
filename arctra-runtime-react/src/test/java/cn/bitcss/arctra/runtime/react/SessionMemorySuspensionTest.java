package cn.bitcss.arctra.runtime.react;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * M4-T3 MEMORY CLOSURE GATE: Session memory across suspension/resume.
 *
 * <p>Tests whether ChatMemory correctly persists conversation state through:
 * <ul>
 *   <li>Initial execution → suspension</li>
 *   <li>Resume → completion</li>
 *   <li>Next turn in same session</li>
 * </ul>
 *
 * <p>CRITICAL INVARIANTS:
 * <ul>
 *   <li>NO DUPLICATION: Each message appears exactly once in history</li>
 *   <li>NO LOSS: Final resumed AssistantMessage is visible to next turn</li>
 *   <li>NO SUSPENSION PLACEHOLDER in durable memory</li>
 * </ul>
 */
class SessionMemorySuspensionTest {

  @Test
  void sessionMemory_acrossSuspensionAndResume() {
    System.out.println("\n" + "=".repeat(80));
    System.out.println("M4-T3 SESSION MEMORY SUSPENSION TEST");
    System.out.println("=".repeat(80));

    String sessionId = "S1";
    ChatMemory chatMemory = MessageWindowChatMemory.builder().maxMessages(100).build();

    // ========================================
    // PRE-POPULATE SESSION HISTORY
    // ========================================
    System.out.println("\n=== PRE-POPULATING SESSION HISTORY ===");
    chatMemory.add(sessionId, new UserMessage("My service is payment"));
    chatMemory.add(sessionId, new AssistantMessage("Understood"));

    List<Message> memoryBeforeTest = chatMemory.get(sessionId);
    System.out.println("Memory before test:");
    printMemory(memoryBeforeTest);

    // ========================================
    // SETUP: ChatModel and Tool
    // ========================================
    AtomicInteger toolExecutionCount = new AtomicInteger(0);
    List<List<Message>> capturedModelCalls = new ArrayList<>();

    ChatModel chatModel = new ChatModel() {
      private int callCount = 0;

      @Override
      public ChatResponse call(Prompt prompt) {
        callCount++;
        System.out.println("\n[ChatModel] Call #" + callCount);

        // Capture messages
        List<Message> messages = new ArrayList<>(prompt.getInstructions());
        capturedModelCalls.add(messages);

        System.out.println("  Received " + messages.size() + " messages:");
        for (int i = 0; i < messages.size(); i++) {
          Message msg = messages.get(i);
          String preview = getPreview(msg);
          System.out.println("    [" + i + "] " + msg.getClass().getSimpleName() + ": " + preview);
        }

        boolean hasToolResponse = messages.stream()
            .anyMatch(m -> m instanceof org.springframework.ai.chat.messages.ToolResponseMessage);

        if (!hasToolResponse) {
          // Initial call: return tool call
          System.out.println("  → Returning ToolCall");
          return new ChatResponse(List.of(new Generation(
              AssistantMessage.builder()
                  .content("Let me investigate")
                  .toolCalls(List.of(new AssistantMessage.ToolCall(
                      "call_investigate", "function", "investigate", "{}")))
                  .build())));
        } else {
          // After tool execution: final answer
          System.out.println("  → Returning final answer");
          return new ChatResponse(List.of(new Generation(
              new AssistantMessage("Root cause is database timeout"))));
        }
      }

      @Override
      public ChatOptions getOptions() {
        return org.springframework.ai.model.tool.ToolCallingChatOptions.builder().build();
      }
    };

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
      public String call(String args) {
        int count = toolExecutionCount.incrementAndGet();
        System.out.println("[Tool] investigate executed #" + count);
        return "investigation result";
      }
    };

    ToolGovernancePolicy policy = (toolName, args, ctx) -> {
      System.out.println("[Governance] REQUIRE_APPROVAL for: " + toolName);
      return GovernanceDecision.REQUIRE_APPROVAL;
    };

    var engine = new SpringAiToolCallingEngine(chatModel, List.of(tool), chatMemory, policy);
    AgentRuntime runtime = new DefaultAgentRuntime(engine);
    var definition = new AgentDefinition("test", "Test agent");
    var agent = runtime.agent(definition);

    // ========================================
    // PHASE 1: Initial execution → SUSPEND
    // ========================================
    System.out.println("\n" + "=".repeat(80));
    System.out.println("PHASE 1: INITIAL EXECUTION (EXPECT SUSPENSION)");
    System.out.println("=".repeat(80));

    AgentExecutionContext context = AgentExecutionContext.withSession(sessionId);
    AgentResult result1 = agent.execute(
        new AgentRequest("Investigate the incident"),
        context
    );

    System.out.println("\n=== PHASE 1 RESULTS ===");
    System.out.println("Suspended: " + result1.isSuspended());
    System.out.println("Content: " + result1.content());
    assertThat(result1.isSuspended()).isTrue();

    // CAPTURE ChatMemory immediately after suspension
    List<Message> memoryAfterSuspension = chatMemory.get(sessionId);
    System.out.println("\n=== ChatMemory IMMEDIATELY AFTER SUSPENSION ===");
    printMemory(memoryAfterSuspension);

    // Check whether suspension placeholder is persisted
    boolean suspensionPlaceholderPersisted = memoryAfterSuspension.stream()
        .anyMatch(m -> m instanceof AssistantMessage &&
            m.getText() != null &&
            m.getText().contains("Execution suspended"));

    System.out.println("\n=== SUSPENSION PLACEHOLDER CHECK ===");
    System.out.println("Suspension placeholder persisted: " + (suspensionPlaceholderPersisted ? "YES" : "NO"));

    // ========================================
    // PHASE 2: Resume → COMPLETE
    // ========================================
    System.out.println("\n" + "=".repeat(80));
    System.out.println("PHASE 2: RESUME (EXPECT COMPLETION)");
    System.out.println("=".repeat(80));

    AgentProcess process = result1.process();
    AgentResult result2 = process.resume(new ApprovalSignal(true, "Approved"));

    System.out.println("\n=== PHASE 2 RESULTS ===");
    System.out.println("Completed: " + result2.isCompleted());
    System.out.println("Content: " + result2.content());
    assertThat(result2.isCompleted()).isTrue();
    assertThat(result2.content()).contains("Root cause is database timeout");

    // CAPTURE ChatMemory immediately after completion
    List<Message> memoryAfterCompletion = chatMemory.get(sessionId);
    System.out.println("\n=== ChatMemory IMMEDIATELY AFTER COMPLETION ===");
    printMemory(memoryAfterCompletion);

    // ========================================
    // PHASE 3: Next turn in same session
    // ========================================
    System.out.println("\n" + "=".repeat(80));
    System.out.println("PHASE 3: NEXT TURN IN SAME SESSION (ACCEPTANCE TEST)");
    System.out.println("=".repeat(80));

    // Clear captured calls
    capturedModelCalls.clear();

    // Create a simple model that just echoes what it sees
    ChatModel echoModel = new ChatModel() {
      @Override
      public ChatResponse call(Prompt prompt) {
        System.out.println("\n[EchoModel] Call");

        List<Message> messages = new ArrayList<>(prompt.getInstructions());
        capturedModelCalls.add(messages);

        System.out.println("  Received " + messages.size() + " messages:");
        for (int i = 0; i < messages.size(); i++) {
          Message msg = messages.get(i);
          String preview = getPreview(msg);
          System.out.println("    [" + i + "] " + msg.getClass().getSimpleName() + ": " + preview);
        }

        return new ChatResponse(List.of(new Generation(
            new AssistantMessage("The previous root cause was database timeout"))));
      }

      @Override
      public ChatOptions getOptions() {
        return org.springframework.ai.model.tool.ToolCallingChatOptions.builder().build();
      }
    };

    // Create new engine with echo model (no tools, no governance)
    var echoEngine = new SpringAiToolCallingEngine(echoModel, List.of(), chatMemory);
    AgentRuntime echoRuntime = new DefaultAgentRuntime(echoEngine);
    var echoAgent = echoRuntime.agent(definition);

    AgentResult result3 = echoAgent.execute(
        new AgentRequest("What was the root cause?"),
        context  // Same session S1
    );

    System.out.println("\n=== PHASE 3 RESULTS ===");
    System.out.println("Content: " + result3.content());

    // ========================================
    // VERIFICATION: NO DUPLICATION, NO LOSS
    // ========================================
    System.out.println("\n" + "=".repeat(80));
    System.out.println("MEMORY INVARIANT VERIFICATION");
    System.out.println("=".repeat(80));

    List<Message> nextTurnModelMessages = capturedModelCalls.get(0);

    // Count occurrences of each key message
    long countPayment = countMessageContaining(nextTurnModelMessages, "My service is payment");
    long countUnderstood = countMessageContaining(nextTurnModelMessages, "Understood");
    long countInvestigate = countMessageContaining(nextTurnModelMessages, "Investigate the incident");
    long countRootCause = countMessageContaining(nextTurnModelMessages, "Root cause is database timeout");
    long countSuspensionPlaceholder = countMessageContaining(nextTurnModelMessages, "Execution suspended");

    System.out.println("\n=== MESSAGE OCCURRENCE COUNTS ===");
    System.out.println("\"My service is payment\": " + countPayment);
    System.out.println("\"Understood\": " + countUnderstood);
    System.out.println("\"Investigate the incident\": " + countInvestigate);
    System.out.println("\"Root cause is database timeout\": " + countRootCause);
    System.out.println("\"Execution suspended\" placeholder: " + countSuspensionPlaceholder);

    // ASSERTIONS
    System.out.println("\n=== INVARIANT ASSERTIONS ===");

    assertThat(countPayment).as("\"My service is payment\" should appear exactly once").isEqualTo(1);
    System.out.println("✓ \"My service is payment\" exactly once");

    assertThat(countUnderstood).as("\"Understood\" should appear exactly once").isEqualTo(1);
    System.out.println("✓ \"Understood\" exactly once");

    assertThat(countInvestigate).as("\"Investigate the incident\" should appear exactly once").isEqualTo(1);
    System.out.println("✓ \"Investigate the incident\" exactly once");

    assertThat(countRootCause).as("\"Root cause is database timeout\" should appear exactly once").isEqualTo(1);
    System.out.println("✓ \"Root cause is database timeout\" exactly once (NO LOSS)");

    assertThat(countSuspensionPlaceholder).as("Suspension placeholder should NOT appear in next turn").isEqualTo(0);
    System.out.println("✓ No suspension placeholder in conversational history");

    System.out.println("\n" + "=".repeat(80));
    System.out.println("✅ SESSION MEMORY SUSPENSION TEST PASSED");
    System.out.println("=".repeat(80));
  }

  private String getPreview(Message msg) {
    String text = msg.getText();
    if (text == null) {
      text = msg.toString();
    }
    return text.length() > 60 ? text.substring(0, 60) + "..." : text;
  }

  private void printMemory(List<Message> messages) {
    for (int i = 0; i < messages.size(); i++) {
      Message msg = messages.get(i);
      String preview = getPreview(msg);
      System.out.println("  [" + i + "] " + msg.getClass().getSimpleName() + ": " + preview);
    }
    System.out.println("  Total: " + messages.size() + " messages");
  }

  private long countMessageContaining(List<Message> messages, String substring) {
    return messages.stream()
        .filter(m -> {
          String text = m.getText();
          return text != null && text.contains(substring);
        })
        .count();
  }
}
