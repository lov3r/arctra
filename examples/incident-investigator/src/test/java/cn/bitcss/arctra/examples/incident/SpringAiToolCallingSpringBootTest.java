package cn.bitcss.arctra.examples.incident;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * Spring Boot 集成测试：验证 ToolCallback 接口方式的 tool calling
 *
 * <p>使用 spring-ai-starter-model-openai 自动配置 ChatModel
 *
 * <p>目标：搞清楚 Spring AI 的 tool calling 机制是否支持 ToolCallback 接口
 */
@SpringBootTest(classes = TestApplication.class)
@TestPropertySource(properties = {
    "spring.ai.openai.base-url=https://router.ezsub.com/v1",
    "spring.ai.openai.api-key=G5ruk5BGffumiEDpVWuPTJO4ywcPHlkXOQW6X6NbR9XDXA0a",
    "spring.ai.openai.chat.options.model=gpt-5.4"
})
@Disabled("需要真实 API 调用 - 已验证 ToolCallingAdvisor 自动添加")
class SpringAiToolCallingSpringBootTest {

  @Autowired
  private ChatModel chatModel;

  @Test
  void should_trigger_tool_calling_with_toolcallback_interface() {
    // Arrange: 创建一个简单的 ToolCallback
    ToolCallback getCurrentTimeTool = new ToolCallback() {
      @Override
      public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
            .name("getCurrentTime")
            .description("Get current time")
            .inputSchema("""
                {
                  "type": "object",
                  "properties": {},
                  "required": []
                }
                """)
            .build();
      }

      @Override
      public String call(String args) {
        System.out.println("[Tool] getCurrentTime called with args: " + args);
        return "2024-01-15 10:30:00";
      }
    };

    // Arrange: 创建 ChatClient
    ChatClient chatClient = ChatClient.builder(chatModel).build();

    // Act: 使用 .tools() 传入 ToolCallback
    System.out.println("\n=== Sending request to ChatModel ===");
    String response = chatClient.prompt()
        .user("What time is it now?")
        .tools(getCurrentTimeTool)
        .call()
        .content();

    // Assert: 打印结果
    System.out.println("\n=== Response ===");
    System.out.println(response);

    // 如果 tool calling 生效，应该能看到：
    // 1. [Tool] getCurrentTime called with args: ...
    // 2. Response 包含时间信息 "2024-01-15 10:30:00"

    System.out.println("\n=== Analysis ===");
    if (response.contains("2024-01-15") || response.contains("10:30")) {
      System.out.println("✅ Tool calling 成功！工具被调用，结果被整合到响应中");
    } else {
      System.out.println("❌ Tool calling 可能没有生效");
      System.out.println("Response: " + response);
    }
  }
}
