package cn.bitcss.arctra.examples.incident.tools;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link QueryLogsTool}.
 *
 * @author lov3r
 */
class QueryLogsToolTest {

  @Test
  void should_return_correct_tool_definition() {
    var tool = new QueryLogsTool();
    var definition = tool.getToolDefinition();

    assertThat(definition.name()).isEqualTo("queryLogs");
    assertThat(definition.description()).contains("logs");
    assertThat(definition.inputSchema()).isNotBlank();
  }

  @Test
  void should_return_deterministic_mock_logs() {
    var tool = new QueryLogsTool();

    var result1 = tool.call("{}");
    var result2 = tool.call("{}");

    // Deterministic: same input → same output
    assertThat(result1).isEqualTo(result2);
  }

  @Test
  void should_contain_scenario_required_facts() {
    var tool = new QueryLogsTool();
    var result = tool.call("{}");

    // Scenario required facts:
    // 1. 16:20 time range
    assertThat(result).contains("16:20");

    // 2. SQLException with 'user_status' column
    assertThat(result).contains("SQLException");
    assertThat(result).contains("Unknown column 'user_status'");

    // 3. Multiple occurrences
    assertThat(result).contains("count");
  }
}
