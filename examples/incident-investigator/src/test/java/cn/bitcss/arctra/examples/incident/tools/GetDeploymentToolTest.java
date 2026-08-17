package cn.bitcss.arctra.examples.incident.tools;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link GetDeploymentTool}.
 *
 * @author lov3r
 */
class GetDeploymentToolTest {

  @Test
  void should_return_correct_tool_definition() {
    var tool = new GetDeploymentTool();
    var definition = tool.getToolDefinition();

    assertThat(definition.name()).isEqualTo("getDeployment");
    assertThat(definition.description()).contains("deployment");
    assertThat(definition.inputSchema()).isNotBlank();
  }

  @Test
  void should_return_deterministic_deployment_info() {
    var tool = new GetDeploymentTool();

    var result1 = tool.call("{}");
    var result2 = tool.call("{}");

    // Deterministic: same input → same output
    assertThat(result1).isEqualTo(result2);
  }

  @Test
  void should_contain_scenario_required_facts() {
    var tool = new GetDeploymentTool();
    var result = tool.call("{}");

    // Scenario required facts:
    // 1. Version v1.2.3
    assertThat(result).contains("v1.2.3");

    // 2. Deployed at 16:18 (before error at 16:20)
    assertThat(result).contains("16:18");

    // 3. Added user_status field (cause of schema drift)
    assertThat(result).contains("user_status");
    assertThat(result).contains("User entity");
  }
}
