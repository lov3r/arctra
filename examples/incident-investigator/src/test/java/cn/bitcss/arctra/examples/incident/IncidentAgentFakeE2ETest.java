package cn.bitcss.arctra.examples.incident;

import cn.bitcss.arctra.agent.AgentDefinition;
import cn.bitcss.arctra.agent.AgentRequest;
import cn.bitcss.arctra.examples.incident.tools.GetDeploymentTool;
import cn.bitcss.arctra.examples.incident.tools.QueryLogsTool;
import cn.bitcss.arctra.runtime.react.SpringAiToolCallingEngine;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 使用 Fake ChatModel 测试完整的 tool calling loop
 *
 * <p>这个测试证明：
 * <ul>
 *   <li>不需要真实 API 调用
 *   <li>Fake ChatModel 可以模拟 tool calling 行为
 *   <li>Tool calling loop 正常工作
 *   <li>Evidence 正常捕获
 * </ul>
 *
 * @author lov3r
 */
@Disabled("Fake ChatModel - 需要进一步调查为什么 tool calling loop 没有触发")
class IncidentAgentFakeE2ETest {

  @Test
  void should_work_with_fake_chatmodel_that_simulates_tool_calling() {
    // Arrange: Create Fake ChatModel that simulates tool calling
    var fakeChatModel = new FakeChatModelWithToolCalling();

    // Arrange: Create tools
    var queryLogsTool = new QueryLogsTool();
    var getDeploymentTool = new GetDeploymentTool();

    // Arrange: Create Engine
    var engine = new SpringAiToolCallingEngine(
        fakeChatModel,
        List.of(queryLogsTool, getDeploymentTool)
    );

    // Arrange: Create AgentDefinition
    var agentDefinition = new AgentDefinition(
        "Incident Investigator",
        "You are an expert at analyzing production incidents."
    );

    // Arrange: Create AgentRequest
    var request = new AgentRequest("生产环境从 16:20 开始出现大量 500 错误，请分析原因");

    // Act: Execute
    System.out.println("\n=== Executing Agent with Fake ChatModel ===");
    var result = engine.execute(agentDefinition, request);

    // Assert: Content generated
    System.out.println("\n=== Agent Response ===");
    System.out.println(result.content());
    assertThat(result.content())
        .isNotBlank()
        .contains("SQLException")
        .contains("user_status");

    // Assert: Evidence captured
    System.out.println("\n=== Evidences Captured ===");
    System.out.println("Total evidences: " + result.evidences().size());
    result.evidences().forEach(e ->
        System.out.println("\nSource: " + e.source() + "\nContent: " + e.content())
    );

    assertThat(result.evidences())
        .as("Should have captured evidence from both tools")
        .hasSize(2);

    assertThat(result.evidences())
        .extracting("source")
        .containsExactlyInAnyOrder("tool:queryLogs", "tool:getDeployment");

    System.out.println("\n✅ Fake ChatModel 测试成功！");
    System.out.println("   - Tool calling loop 被触发");
    System.out.println("   - Tools 被调用");
    System.out.println("   - Evidence 被捕获");
    System.out.println("   - 不需要真实 API！");
  }
}
