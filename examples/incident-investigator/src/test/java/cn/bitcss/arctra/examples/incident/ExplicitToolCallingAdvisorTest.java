//package cn.bitcss.arctra.examples.incident;
//
//import org.junit.jupiter.api.Test;
//import org.springframework.ai.chat.client.ChatClient;
//import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
//import org.springframework.ai.chat.messages.AssistantMessage;
//import org.springframework.ai.chat.messages.MessageType;
//import org.springframework.ai.chat.model.ChatModel;
//import org.springframework.ai.chat.model.ChatResponse;
//import org.springframework.ai.chat.model.Generation;
//import org.springframework.ai.chat.prompt.ChatOptions;
//import org.springframework.ai.chat.prompt.Prompt;
//import org.springframework.ai.tool.ToolCallback;
//import org.springframework.ai.tool.definition.ToolDefinition;
//
//import java.util.List;
//
///**
// * 测试显式添加 ToolCallingAdvisor
// */
//class ExplicitToolCallingAdvisorTest {
//
//  @Test
//  void should_work_with_explicit_toolcalling_advisor() {
//    // Arrange: Fake ChatModel
//    ChatModel fakeChatModel = new ChatModel() {
//      private int callCount = 0;
//
//      @Override
//      public ChatResponse call(Prompt prompt) {
//        callCount++;
//        System.out.println("\n[FakeChatModel] Call #" + callCount);
//
//        boolean hasToolResponse = prompt.getInstructions().stream()
//            .anyMatch(msg -> msg.getMessageType() == MessageType.TOOL);
//
//        if (hasToolResponse) {
//          System.out.println("[FakeChatModel] Tool response detected, returning final answer");
//          return new ChatResponse(List.of(
//              new Generation(new AssistantMessage("Final answer with tool results"))
//          ));
//        } else {
//          System.out.println("[FakeChatModel] Returning ToolCall");
//          var assistantMessage = AssistantMessage.builder()
//              .content("")
//              .toolCalls(List.of(
//                  new AssistantMessage.ToolCall(
//                      "call_test_001",
//                      "function",
//                      "testTool",
//                      "{}"
//                  )
//              ))
//              .build();
//          return new ChatResponse(List.of(new Generation(assistantMessage)));
//        }
//      }
//
//      @Override
//      public ChatOptions getDefaultOptions() {
//        return null;
//      }
//    };
//
//    // Arrange: ToolCallback
//    ToolCallback testTool = new ToolCallback() {
//      @Override
//      public ToolDefinition getToolDefinition() {
//        return ToolDefinition.builder()
//            .name("testTool")
//            .description("A test tool")
//            .inputSchema("{\"type\":\"object\",\"properties\":{}}")
//            .build();
//      }
//
//      @Override
//      public String call(String args) {
//        System.out.println("\n[Tool] testTool called!");
//        return "Tool result";
//      }
//    };
//
//    // Test 1: Without explicit advisor (baseline)
//    System.out.println("\n=== Test 1: Without explicit ToolCallingAdvisor ===");
//    ChatClient chatClient1 = ChatClient.builder(fakeChatModel).build();
//    String result1 = chatClient1.prompt()
//        .user("Call the test tool")
//        .tools(testTool)
//        .call()
//        .content();
//    System.out.println("Result: " + result1);
//    System.out.println(result1.isEmpty() ? "❌ Failed" : "✅ Success");
//
//    // Test 2: With explicit ToolCallingAdvisor
//    System.out.println("\n=== Test 2: With explicit ToolCallingAdvisor ===");
//    ChatClient chatClient2 = ChatClient.builder(fakeChatModel)
//        .defaultAdvisors(new ToolCallingAdvisor())
//        .build();
//    String result2 = chatClient2.prompt()
//        .user("Call the test tool")
//        .tools(testTool)
//        .call()
//        .content();
//    System.out.println("Result: " + result2);
//    System.out.println(result2.contains("Final answer") ? "✅ Success - Tool calling loop worked!" : "❌ Failed");
//  }
//}
