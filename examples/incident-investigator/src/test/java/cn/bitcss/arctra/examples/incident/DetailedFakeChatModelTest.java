package cn.bitcss.arctra.examples.incident;

import cn.bitcss.arctra.agent.AgentDefinition;
import cn.bitcss.arctra.agent.AgentRequest;
import cn.bitcss.arctra.examples.incident.tools.GetDeploymentTool;
import cn.bitcss.arctra.examples.incident.tools.QueryLogsTool;
import cn.bitcss.arctra.runtime.react.SpringAiToolCallingEngine;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 详细的 Fake ChatModel 测试 - 带完整的日志输出
 *
 * <p>这个测试帮助我们理解为什么 tool calling loop 没有触发
 */
@Disabled("调试用 - 手动启用")
class DetailedFakeChatModelTest {

  @Test
  void should_investigate_why_tool_calling_loop_not_triggered() {
    // Arrange: 创建一个详细记录的 Fake ChatModel
    ChatModel fakeChatModel = new ChatModel() {
      private int callCount = 0;

      @Override
      public ChatResponse call(Prompt prompt) {
        callCount++;
        System.out.println("\n" + "=".repeat(60));
        System.out.println("ChatModel.call() #" + callCount);
        System.out.println("=".repeat(60));

        System.out.println("Prompt has " + prompt.getInstructions().size() + " messages:");
        prompt.getInstructions().forEach(msg -> {
          System.out.println("  [" + msg.getMessageType() + "] " +
              msg.getText().substring(0, Math.min(100, msg.getText().length())) + "...");
        });

        boolean hasToolResponse = prompt.getInstructions().stream()
            .anyMatch(msg -> msg.getMessageType() == MessageType.TOOL);

        if (hasToolResponse) {
          System.out.println("\n✓ Tool response detected - returning final answer");
          return new ChatResponse(List.of(
              new Generation(new AssistantMessage(
                  "根据日志和部署信息分析，16:20 的 500 错误是由于 v1.2.3 部署引入了 user_status 字段但数据库未更新。"
              ))
          ));
        } else {
          System.out.println("\n→ First call - returning ToolCalls to trigger tools");
          var assistantMessage = AssistantMessage.builder()
              .content("")
              .toolCalls(List.of(
                  new AssistantMessage.ToolCall("call_1", "function", "queryLogs", "{}"),
                  new AssistantMessage.ToolCall("call_2", "function", "getDeployment", "{}")
              ))
              .build();
          System.out.println("  Returning 2 ToolCalls: queryLogs, getDeployment");
          return new ChatResponse(List.of(new Generation(assistantMessage)));
        }
      }

      @Override
      public ChatOptions getDefaultOptions() {
        return null;
      }
    };

    // Arrange: Create tools with logging
    var queryLogsTool = new QueryLogsTool() {
      @Override
      public String call(String args) {
        System.out.println("\n[QueryLogsTool] CALLED with args: " + args);
        String result = super.call(args);
        System.out.println("[QueryLogsTool] Returning result (first 100 chars): " +
            result.substring(0, Math.min(100, result.length())));
        return result;
      }
    };

    var getDeploymentTool = new GetDeploymentTool() {
      @Override
      public String call(String args) {
        System.out.println("\n[GetDeploymentTool] CALLED with args: " + args);
        String result = super.call(args);
        System.out.println("[GetDeploymentTool] Returning result (first 100 chars): " +
            result.substring(0, Math.min(100, result.length())));
        return result;
      }
    };

    // Arrange: Create Engine
    var engine = new SpringAiToolCallingEngine(
        fakeChatModel,
        List.of(queryLogsTool, getDeploymentTool)
    );

    // Arrange: Create Agent
    var agentDefinition = new AgentDefinition(
        "Incident Investigator",
        "Analyze production incidents using available tools."
    );
    var request = new AgentRequest("生产环境从 16:20 开始出现大量 500 错误，请分析原因");

    // Act
    System.out.println("\n" + "=".repeat(60));
    System.out.println("EXECUTING AGENT");
    System.out.println("=".repeat(60));
    var result = engine.execute(agentDefinition, request);

    // Assert & Report
    System.out.println("\n" + "=".repeat(60));
    System.out.println("FINAL RESULTS");
    System.out.println("=".repeat(60));
    System.out.println("Response length: " + result.content().length());
    System.out.println("Response: " + result.content());
    System.out.println("\nEvidences captured: " + result.evidences().size());
    result.evidences().forEach(e ->
        System.out.println("  - " + e.source() + ": " +
            e.content().substring(0, Math.min(50, e.content().length())))
    );

    System.out.println("\n" + "=".repeat(60));
    System.out.println("ANALYSIS");
    System.out.println("=".repeat(60));

    if (result.evidences().size() >= 2) {
      System.out.println("✅ SUCCESS: Tool calling loop worked!");
      System.out.println("   - Tools were called");
      System.out.println("   - Evidence was captured");
      System.out.println("   - ChatModel was called multiple times");
    } else if (result.evidences().size() == 0) {
      System.out.println("❌ FAILED: Tool calling loop did NOT trigger");
      System.out.println("   - ChatModel was called once");
      System.out.println("   - No tools were executed");
      System.out.println("   - ToolCallingAdvisor was NOT active");
    } else {
      System.out.println("⚠️  PARTIAL: Some tools called but not all");
    }
  }
}
