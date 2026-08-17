package cn.bitcss.arctra.examples.incident;

import static org.assertj.core.api.Assertions.assertThat;

import cn.bitcss.arctra.agent.AgentDefinition;
import cn.bitcss.arctra.agent.AgentRequest;
import cn.bitcss.arctra.examples.incident.tools.GetDeploymentTool;
import cn.bitcss.arctra.examples.incident.tools.QueryLogsTool;
import cn.bitcss.arctra.runtime.react.SpringAiToolCallingEngine;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * E2E structure test for Incident Agent.
 *
 * <p>This test validates that all components can be correctly assembled and the engine can execute
 * without actual LLM tool calling (which requires real OpenAI API - see IncidentAgentRealE2ETest).
 *
 * @author lov3r
 */
class IncidentAgentE2EStructureTest {

  @Test
  void should_execute_engine_with_fake_model() {
    // Arrange: Create tools
    var queryLogsTool = new QueryLogsTool();
    var getDeploymentTool = new GetDeploymentTool();

    // Arrange: Create Fake ChatModel (returns simple response without tool calling)
    var fakeChatModel = new FakeChatModelWithToolCalling();

    // Arrange: Create Engine
    var engine =
        new SpringAiToolCallingEngine(fakeChatModel, List.of(queryLogsTool, getDeploymentTool));

    // Arrange: Create AgentDefinition
    var agentDefinition =
        new AgentDefinition(
            "Incident Investigator", "You are an expert at analyzing production incidents.");

    // Arrange: Create AgentRequest
    var request = new AgentRequest("生产环境从 16:20 开始出现大量 500 错误，请分析原因");

    // Act: Execute (fake model won't actually call tools, but engine should not fail)
    var result = engine.execute(agentDefinition, request);

    // Assert: Engine executes successfully
    assertThat(result.content()).isNotBlank();

    // Note: Fake model doesn't actually trigger tool calling, so evidences will be empty
    // For real tool calling with evidence capture, see IncidentAgentRealE2ETest
    assertThat(result.evidences()).isNotNull();

    System.out.println("=== Fake Model Response ===");
    System.out.println(result.content());
    System.out.println();
    System.out.println(
        "Note: This fake model doesn't trigger actual tool calling. "
            + "For real tool calling behavior, enable and run IncidentAgentRealE2ETest.");
  }

  @Test
  void should_construct_all_components() {
    // Verify tools can be constructed
    var queryLogsTool = new QueryLogsTool();
    var getDeploymentTool = new GetDeploymentTool();

    assertThat(queryLogsTool).isNotNull();
    assertThat(getDeploymentTool).isNotNull();

    // Verify tools return expected mock data
    var logsResult = queryLogsTool.call("{}");
    assertThat(logsResult).contains("SQLException");
    assertThat(logsResult).contains("user_status");

    var deploymentResult = getDeploymentTool.call("{}");
    assertThat(deploymentResult).contains("v1.2.3");
    assertThat(deploymentResult).contains("16:18");
  }

  @Test
  void should_construct_agent_definition() {
    var agentDefinition =
        new AgentDefinition(
            "Incident Investigator", "You are an expert at analyzing production incidents.");

    assertThat(agentDefinition.name()).isEqualTo("Incident Investigator");
    assertThat(agentDefinition.description()).contains("production incidents");
  }

  @Test
  void should_construct_agent_request() {
    var request = new AgentRequest("生产环境从 16:20 开始出现大量 500 错误，请分析原因");

    assertThat(request.userMessage()).contains("500 错误");
  }
}
