package cn.bitcss.arctra.runtime.react;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
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
 * Baseline: Standard Spring AI tool calling + memory behavior.
 *
 * <p>Characterizes what messages Spring AI persists for normal tool execution.
 */
class SpringAiMemoryBaselineTest {

  @Test
  void baseline_normalToolCallMemoryPersistence() {
    System.out.println("\n" + "=".repeat(80));
    System.out.println("SPRING AI BASELINE: NORMAL TOOL CALL + MEMORY");
    System.out.println("=".repeat(80));

    String sessionId = "test-session";
    ChatMemory chatMemory = MessageWindowChatMemory.builder().maxMessages(100).build();

    // Pre-populate
    System.out.println("\n=== PRE-POPULATE MEMORY ===");
    chatMemory.add(sessionId, new UserMessage("Previous user"));
    chatMemory.add(sessionId, new AssistantMessage("Previous assistant"));

    List<Message> memoryBefore = chatMemory.get(sessionId);
    System.out.println("Memory BEFORE:");
    printMemory(memoryBefore);

    // Tool
    ToolCallback tool = new ToolCallback() {
      @Override
      public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
            .name("getTemperature")
            .description("Get temperature")
            .inputSchema("{\"type\":\"object\",\"properties\":{}}")
            .build();
      }

      @Override
      public String call(String args) {
        System.out.println("[Tool] getTemperature executed");
        return "22°C";
      }
    };

    // Model
    ChatModel chatModel = new ChatModel() {
      private int callCount = 0;

      @Override
      public ChatResponse call(Prompt prompt) {
        callCount++;
        System.out.println("\n[ChatModel] Call #" + callCount);

        boolean hasToolResponse = prompt.getInstructions().stream()
            .anyMatch(m -> m instanceof org.springframework.ai.chat.messages.ToolResponseMessage);

        if (!hasToolResponse) {
          // Return tool call
          System.out.println("  → Returning ToolCall");
          return new ChatResponse(List.of(new Generation(
              AssistantMessage.builder()
                  .content("Let me check")
                  .toolCalls(List.of(new AssistantMessage.ToolCall(
                      "call_temp", "function", "getTemperature", "{}")))
                  .build())));
        } else {
          // Final answer
          System.out.println("  → Returning final answer");
          return new ChatResponse(List.of(new Generation(
              new AssistantMessage("The temperature is 22°C"))));
        }
      }

      @Override
      public ChatOptions getOptions() {
        return org.springframework.ai.model.tool.ToolCallingChatOptions.builder().build();
      }
    };

    // Build client with standard Spring AI ToolCallingAdvisor
    ChatClient chatClient = ChatClient.builder(chatModel)
        .defaultTools(tool)  // Standard tool registration
        .build();

    // ========================================
    // Execute with standard Spring AI flow
    // ========================================
    System.out.println("\n=== EXECUTE STANDARD SPRING AI TOOL CALL ===");

    String result = chatClient.prompt()
        .user("What is the temperature?")
        .advisors(spec -> {
          spec.param(ChatMemory.CONVERSATION_ID, sessionId);
          var memoryAdvisor = MessageChatMemoryAdvisor.builder(chatMemory).build();
          spec.advisors(memoryAdvisor);
          // Standard ToolCallingAdvisor auto-registered
        })
        .call()
        .content();

    System.out.println("\n=== RESULT ===");
    System.out.println("Final content: " + result);

    // ========================================
    // INSPECT ChatMemory
    // ========================================
    List<Message> memoryAfter = chatMemory.get(sessionId);
    System.out.println("\n=== ChatMemory AFTER completion ===");
    printMemory(memoryAfter);

    // ========================================
    // ANALYSIS
    // ========================================
    System.out.println("\n" + "=".repeat(80));
    System.out.println("BASELINE ANALYSIS");
    System.out.println("=".repeat(80));

    long userCount = memoryAfter.stream().filter(m -> m instanceof UserMessage).count();
    long assistantCount = memoryAfter.stream().filter(m -> m instanceof AssistantMessage).count();
    long toolResponseCount = memoryAfter.stream()
        .filter(m -> m instanceof org.springframework.ai.chat.messages.ToolResponseMessage)
        .count();

    System.out.println("\nMessage counts:");
    System.out.println("  UserMessage: " + userCount);
    System.out.println("  AssistantMessage: " + assistantCount);
    System.out.println("  ToolResponseMessage: " + toolResponseCount);
    System.out.println("  Total: " + memoryAfter.size());

    boolean containsToolCall = memoryAfter.stream()
        .anyMatch(m -> m instanceof AssistantMessage &&
            ((AssistantMessage) m).getToolCalls() != null &&
            !((AssistantMessage) m).getToolCalls().isEmpty());

    System.out.println("\nContains AssistantMessage with ToolCalls: " + containsToolCall);

    boolean containsFinalAnswer = memoryAfter.stream()
        .anyMatch(m -> m instanceof AssistantMessage &&
            m.getText() != null &&
            m.getText().contains("temperature is 22"));

    System.out.println("Contains final answer: " + containsFinalAnswer);

    // ========================================
    // DETERMINE SPRING AI SEMANTICS
    // ========================================
    System.out.println("\n" + "=".repeat(80));
    System.out.println("SPRING AI MEMORY SEMANTIC");
    System.out.println("=".repeat(80));

    if (memoryAfter.size() == 4) {
      // H + U + A
      System.out.println("\nSemantic: H + U + A (conversation delta only)");
      System.out.println("Tool execution details NOT persisted in memory");
    } else if (containsToolCall && containsFinalAnswer) {
      System.out.println("\nSemantic: H + U + A(ToolCall) + ToolResponse + A(Final)");
      System.out.println("Full tool execution protocol persisted");
    } else {
      System.out.println("\nSemantic: UNKNOWN - inspect actual messages");
    }

    System.out.println("\n" + "=".repeat(80));
  }

  private void printMemory(List<Message> messages) {
    for (int i = 0; i < messages.size(); i++) {
      Message msg = messages.get(i);
      String type = msg.getClass().getSimpleName();
      String preview = msg.getText();

      if (msg instanceof AssistantMessage) {
        AssistantMessage am = (AssistantMessage) msg;
        if (am.getToolCalls() != null && !am.getToolCalls().isEmpty()) {
          preview = "[ToolCall: " + am.getToolCalls().get(0).name() + "] " +
              (preview != null ? preview : "");
        }
      }

      if (preview != null && preview.length() > 50) {
        preview = preview.substring(0, 50) + "...";
      }
      System.out.println("  [" + i + "] " + type + ": " + preview);
    }
    System.out.println("  Total: " + messages.size() + " messages");
  }
}
