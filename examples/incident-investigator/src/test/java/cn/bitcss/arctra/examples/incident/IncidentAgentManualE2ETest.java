package cn.bitcss.arctra.examples.incident;

import cn.bitcss.arctra.agent.AgentDefinition;
import cn.bitcss.arctra.agent.AgentRequest;
import cn.bitcss.arctra.examples.incident.tools.GetDeploymentTool;
import cn.bitcss.arctra.examples.incident.tools.QueryLogsTool;
import cn.bitcss.arctra.runtime.react.SpringAiToolCallingEngine;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 纯手动构建的 E2E 测试 - 不依赖 Spring Boot 自动装配
 *
 * <p>这个测试证明：我们完全可以手动创建 ChatModel，不需要 Spring Boot
 *
 * @author lov3r
 */
@Disabled("需要真实 API 调用")
class IncidentAgentManualE2ETest {

  @Test
  void should_work_with_manually_built_chatmodel() {
    // Arrange: 手动构建 OpenAiChatModel（使用正确的 builder API）
    ChatModel chatModel = OpenAiChatModel.builder()
        .options(OpenAiChatOptions.builder()
            .baseUrl("https://router.ezsub.com/v1")
            .apiKey("G5ruk5BGffumiEDpVWuPTJO4ywcPHlkXOQW6X6NbR9XDXA0a")
            .model("gpt-5.4")
            .temperature(0.7)
            .build())
        .build();

    // Arrange: Create tools
    var queryLogsTool = new QueryLogsTool();
    var getDeploymentTool = new GetDeploymentTool();

    // Arrange: Create Engine
    var engine = new SpringAiToolCallingEngine(
        chatModel,
        List.of(queryLogsTool, getDeploymentTool)
    );

    // Arrange: Create AgentDefinition
    var agentDefinition = new AgentDefinition(
        "Incident Investigator",
        "You are an expert at analyzing production incidents. Use available tools to gather information."
    );

    // Arrange: Create AgentRequest
    var request = new AgentRequest("生产环境从 16:20 开始出现大量 500 错误，请分析原因");

    // Act: Execute
    System.out.println("\n=== Executing Agent (Manual Build) ===");
    var result = engine.execute(agentDefinition, request);

    // Assert: Content generated
    System.out.println("\n=== Agent Response ===");
    System.out.println(result.content());
    assertThat(result.content()).isNotBlank();

    // Assert: Evidence captured
    System.out.println("\n=== Evidences Captured ===");
    System.out.println("Total evidences: " + result.evidences().size());
    result.evidences().forEach(e ->
        System.out.println("\nSource: " + e.source() + "\nContent: " + e.content())
    );

    assertThat(result.evidences())
        .as("Should have captured evidence from tools")
        .isNotEmpty();

    System.out.println("\n✅ 手动构建成功！不需要 Spring Boot 自动装配！");
  }
}
