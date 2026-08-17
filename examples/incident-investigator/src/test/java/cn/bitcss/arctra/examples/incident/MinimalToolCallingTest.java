package cn.bitcss.arctra.examples.incident;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;

/**
 * 最小化测试：调查 ChatClient.tools() 的行为
 */
class MinimalToolCallingTest {

  @Test
  void should_investigate_chatclient_tools_behavior() {
    // Arrange: 创建一个简单的 Fake ChatModel，返回 ToolCall
    ChatModel fakeChatModel = new ChatModel() {
      private int callCount = 0;

      @Override
      public ChatResponse call(Prompt prompt) {
        callCount++;
        System.out.println("\n[FakeChatModel] Call #" + callCount);
        System.out.println("[FakeChatModel] Prompt messages: " + prompt.getInstructions().size());

        prompt.getInstructions().forEach(msg -> {
          System.out.println("  - " + msg.getMessageType() + ": " + msg.getText());
        });

        // 检查是否有 ToolResponseMessage
        boolean hasToolResponse = prompt.getInstructions().stream()
            .anyMatch(msg -> msg.getMessageType() == MessageType.TOOL);

        if (hasToolResponse) {
          System.out.println("[FakeChatModel] Detected tool response, returning final answer");
          return new ChatResponse(List.of(
              new Generation(new AssistantMessage("Final answer based on tool results"))
          ));
        } else {
          System.out.println("[FakeChatModel] First call, returning ToolCall");
          var assistantMessage = AssistantMessage.builder()
              .content("")
              .toolCalls(List.of(
                  new AssistantMessage.ToolCall(
                      "call_test_001",
                      "function",
                      "testTool",
                      "{}"
                  )
              ))
              .build();
          return new ChatResponse(List.of(new Generation(assistantMessage)));
        }
      }

      @Override
      public ChatOptions getDefaultOptions() {
        return null;
      }
    };

    // Arrange: 创建一个简单的 ToolCallback
    ToolCallback testTool = new ToolCallback() {
      @Override
      public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
            .name("testTool")
            .description("A test tool")
            .inputSchema("{\"type\":\"object\",\"properties\":{}}")
            .build();
      }

      @Override
      public String call(String args) {
        System.out.println("\n[ToolCallback] testTool called with args: " + args);
        return "Tool result: success";
      }
    };

    // Arrange: 创建 ChatClient
    ChatClient chatClient = ChatClient.builder(fakeChatModel).build();

    // Act: 调用 ChatClient with tools
    System.out.println("\n=== Test 1: Using .tools() ===");
    String result = chatClient.prompt()
        .user("Call the test tool")
        .tools(testTool)
        .call()
        .content();

    System.out.println("\n=== Result ===");
    System.out.println(result);

    System.out.println("\n=== Analysis ===");
    if (result.contains("Final answer")) {
      System.out.println("✅ Tool calling loop worked! ToolCallingAdvisor was automatically added");
    } else {
      System.out.println("❌ Tool calling loop did NOT work. ToolCallingAdvisor was NOT added");
    }
  }
}
