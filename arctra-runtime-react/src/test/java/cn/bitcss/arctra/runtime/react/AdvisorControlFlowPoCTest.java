package cn.bitcss.arctra.runtime.react;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
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

/**
 * POC: Spring AI advisor control flow when downstream advisor throws.
 *
 * <p>Tests whether throwing from an advisor prevents MessageChatMemoryAdvisor.after()
 * from persisting messages.
 */
class AdvisorControlFlowPoCTest {

  /** Test control signal - internal suspension representation. */
  static final class TestSuspensionSignal extends RuntimeException {
    TestSuspensionSignal() {
      super("Test suspension signal");
    }
  }

  /**
   * Test advisor that throws control signal instead of returning response.
   */
  static class TestSuspendingAdvisor implements CallAdvisor {

    private final List<String> executionLog;

    TestSuspendingAdvisor(List<String> executionLog) {
      this.executionLog = executionLog;
    }

    @Override
    public ChatClientResponse adviseCall(
        ChatClientRequest request,
        CallAdvisorChain chain) {
      executionLog.add("TestSuspendingAdvisor.adviseCall() - BEFORE throw");
      throw new TestSuspensionSignal();
    }

    @Override
    public String getName() {
      return "TestSuspendingAdvisor";
    }

    @Override
    public int getOrder() {
      return 0; // After memory advisor
    }
  }

  @Test
  void advisorControlFlow_throwPreventsMemoryPersistence() {
    System.out.println("\n" + "=".repeat(80));
    System.out.println("ADVISOR CONTROL FLOW POC: EXCEPTION SEMANTICS");
    System.out.println("=".repeat(80));

    String sessionId = "test-session";
    ChatMemory chatMemory = MessageWindowChatMemory.builder().maxMessages(100).build();

    // Pre-populate existing conversation
    System.out.println("\n=== PRE-POPULATE MEMORY ===");
    chatMemory.add(sessionId, new UserMessage("Previous user message"));
    chatMemory.add(sessionId, new AssistantMessage("Previous assistant answer"));

    List<Message> memoryBefore = chatMemory.get(sessionId);
    System.out.println("Memory BEFORE test:");
    printMemory(memoryBefore);

    // Simple echo model
    ChatModel chatModel = new ChatModel() {
      @Override
      public ChatResponse call(Prompt prompt) {
        System.out.println("\n[ChatModel] Should NOT reach here if advisor throws early");
        return new ChatResponse(List.of(new Generation(
            new AssistantMessage("Model response"))));
      }

      @Override
      public ChatOptions getOptions() {
        return org.springframework.ai.model.tool.ToolCallingChatOptions.builder().build();
      }
    };

    // Execution log
    List<String> executionLog = new ArrayList<>();

    // Build ChatClient with advisors
    var memoryAdvisor = MessageChatMemoryAdvisor.builder(chatMemory).build();
    var suspendingAdvisor = new TestSuspendingAdvisor(executionLog);

    ChatClient chatClient = ChatClient.builder(chatModel).build();

    // ========================================
    // Execute with suspension signal
    // ========================================
    System.out.println("\n=== EXECUTE WITH SUSPENSION SIGNAL ===");

    boolean exceptionCaught = false;
    String exceptionType = null;

    try {
      chatClient.prompt()
          .user("Investigate incident")
          .advisors(spec -> {
            spec.param(ChatMemory.CONVERSATION_ID, sessionId);
            spec.advisors(memoryAdvisor);
            spec.advisors(suspendingAdvisor);
          })
          .call()
          .content();
    } catch (TestSuspensionSignal e) {
      exceptionCaught = true;
      exceptionType = "TestSuspensionSignal";
      System.out.println("[Caught] TestSuspensionSignal as expected");
    } catch (Exception e) {
      exceptionCaught = true;
      exceptionType = e.getClass().getSimpleName();
      System.out.println("[Caught] Unexpected exception: " + e.getClass().getName());
      e.printStackTrace();
    }

    // ========================================
    // INSPECT RESULTS
    // ========================================
    System.out.println("\n=== EXECUTION LOG ===");
    for (String log : executionLog) {
      System.out.println("  " + log);
    }

    System.out.println("\n=== EXCEPTION BEHAVIOR ===");
    System.out.println("Exception caught: " + exceptionCaught);
    System.out.println("Exception type: " + exceptionType);

    List<Message> memoryAfter = chatMemory.get(sessionId);
    System.out.println("\n=== ChatMemory AFTER throw ===");
    printMemory(memoryAfter);

    // ========================================
    // CRITICAL QUESTIONS
    // ========================================
    System.out.println("\n" + "=".repeat(80));
    System.out.println("CRITICAL QUESTIONS - ANSWERS");
    System.out.println("=".repeat(80));

    // A. Was new UserMessage persisted?
    boolean userMessagePersisted = memoryAfter.stream()
        .anyMatch(m -> m instanceof UserMessage &&
            m.getText() != null &&
            m.getText().contains("Investigate incident"));

    System.out.println("\nA. Was new UserMessage persisted?");
    System.out.println("   Answer: " + (userMessagePersisted ? "YES" : "NO"));

    // B. Was any new AssistantMessage persisted?
    long assistantCount = memoryAfter.stream()
        .filter(m -> m instanceof AssistantMessage)
        .count();
    boolean newAssistantPersisted = assistantCount > 1; // Was 1 before

    System.out.println("\nB. Was any new AssistantMessage persisted?");
    System.out.println("   Answer: " + (newAssistantPersisted ? "YES" : "NO"));

    // C. Did exception propagate unchanged?
    System.out.println("\nC. Did exception propagate unchanged?");
    System.out.println("   Answer: " + ("TestSuspensionSignal".equals(exceptionType) ? "YES" : "NO"));

    // D. Memory comparison
    System.out.println("\nD. Memory size comparison:");
    System.out.println("   Before: " + memoryBefore.size() + " messages");
    System.out.println("   After:  " + memoryAfter.size() + " messages");

    // ========================================
    // ASSERTIONS
    // ========================================
    System.out.println("\n" + "=".repeat(80));
    System.out.println("VERIFICATION");
    System.out.println("=".repeat(80));

    assertThat(exceptionCaught).isTrue();
    System.out.println("✓ Exception was thrown and caught");

    assertThat(exceptionType).isEqualTo("TestSuspensionSignal");
    System.out.println("✓ Exception propagated unchanged");

    // Report findings
    if (!userMessagePersisted && !newAssistantPersisted) {
      System.out.println("\n✅ IDEAL RESULT: Throw prevents BOTH user and assistant persistence");
      System.out.println("   → Internal control signal is HIGHLY VIABLE");
    } else if (userMessagePersisted && !newAssistantPersisted) {
      System.out.println("\n⚠️  PARTIAL: UserMessage persisted, AssistantMessage NOT persisted");
      System.out.println("   → Need to evaluate conversation turn semantics");
    } else if (!userMessagePersisted && newAssistantPersisted) {
      System.out.println("\n❌ UNEXPECTED: AssistantMessage persisted without UserMessage");
    } else {
      System.out.println("\n❌ BOTH persisted despite throw");
    }

    System.out.println("\n" + "=".repeat(80));
  }

  private void printMemory(List<Message> messages) {
    for (int i = 0; i < messages.size(); i++) {
      Message msg = messages.get(i);
      String preview = msg.getText();
      if (preview != null && preview.length() > 60) {
        preview = preview.substring(0, 60) + "...";
      }
      System.out.println("  [" + i + "] " + msg.getClass().getSimpleName() + ": " + preview);
    }
    System.out.println("  Total: " + messages.size() + " messages");
  }
}
