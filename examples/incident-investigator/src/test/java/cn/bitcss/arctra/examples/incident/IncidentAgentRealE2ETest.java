package cn.bitcss.arctra.examples.incident;

import cn.bitcss.arctra.agent.AgentDefinition;
import cn.bitcss.arctra.agent.AgentRequest;
import cn.bitcss.arctra.examples.incident.tools.GetDeploymentTool;
import cn.bitcss.arctra.examples.incident.tools.QueryLogsTool;
import cn.bitcss.arctra.runtime.react.SpringAiToolCallingEngine;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E2E 测试：完整验证 SpringAiToolCallingEngine 与真实 ChatModel
 *
 * <p>验证完整流程：
 * <ul>
 *   <li>SpringAiToolCallingEngine
 *   <li>真实的 ChatModel (OpenAI)
 *   <li>Tools (QueryLogsTool, GetDeploymentTool)
 *   <li>Evidence 捕获
 * </ul>
 */
@SpringBootTest(classes = TestApplication.class)
@TestPropertySource(properties = {
    "spring.ai.openai.base-url=https://router.ezsub.com/v1",
    "spring.ai.openai.api-key=G5ruk5BGffumiEDpVWuPTJO4ywcPHlkXOQW6X6NbR9XDXA0a",
    "spring.ai.openai.chat.options.model=gpt-5.4"
})
@Disabled("需要真实 API 调用 - 手动启用以验证完整 E2E")
class IncidentAgentRealE2ETest {

  @Autowired
  private ChatModel chatModel;

  @Test
  void should_execute_complete_agent_flow_with_real_chatmodel() {
    // Arrange: Create tools
    var queryLogsTool = new QueryLogsTool();
    var getDeploymentTool = new GetDeploymentTool();

    // Arrange: Create Engine with real ChatModel
    var engine = new SpringAiToolCallingEngine(
        chatModel,
        List.of(queryLogsTool, getDeploymentTool)
    );

    // Arrange: Create AgentDefinition
    var agentDefinition = new AgentDefinition(
        "Incident Investigator",
        "You are an expert at analyzing production incidents. When investigating, always use the available tools to gather information."
    );

    // Arrange: Create AgentRequest
    var request = new AgentRequest("生产环境从 16:20 开始出现大量 500 错误，请分析原因");

    // Act: Execute
    System.out.println("\n=== Executing Agent ===");
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

    // 如果 tool calling 和 evidence capture 都工作，应该看到：
    // 1. Response 提到了 SQLException 和 user_status
    // 2. Evidences 包含了 tool:queryLogs 和 tool:getDeployment
    assertThat(result.evidences())
        .as("Should have captured evidence from tools")
        .isNotEmpty();

    System.out.println("\n=== E2E Validation ===");
    if (result.evidences().size() >= 2) {
      System.out.println("✅ 完整 E2E 验证成功！");
      System.out.println("   - SpringAiToolCallingEngine 正常工作");
      System.out.println("   - Tool calling loop 被触发");
      System.out.println("   - Evidence 被正确捕获");
    } else {
      System.out.println("⚠️  Evidence 捕获数量不符合预期");
      System.out.println("   期望: >= 2, 实际: " + result.evidences().size());
    }
  }
}
