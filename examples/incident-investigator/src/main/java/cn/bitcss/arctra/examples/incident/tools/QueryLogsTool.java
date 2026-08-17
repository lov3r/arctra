package cn.bitcss.arctra.examples.incident.tools;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * Mock tool for querying application logs (Incident Scenario fixture).
 *
 * <p>Returns fixed mock data for M1 E2E testing. This is a scenario-specific fixture, not a
 * framework capability.
 *
 * @author lov3r
 */
public class QueryLogsTool implements ToolCallback {

  @Override
  public ToolDefinition getToolDefinition() {
    return ToolDefinition.builder()
        .name("queryLogs")
        .description("Query application logs to investigate production errors")
        .inputSchema(
            """
            {
              "type": "object",
              "properties": {}
            }
            """)
        .build();
  }

  @Override
  public String call(String functionArguments) {
    // Return fixed mock logs for Incident Scenario
    // Time: 16:20-16:22, Error: Unknown column 'user_status'
    return """
        {
          "logs": [
            "16:20:15 ERROR SQLException: Unknown column 'user_status' in 'field list'",
            "16:20:18 ERROR SQLException: Unknown column 'user_status' in 'field list'",
            "16:20:22 ERROR SQLException: Unknown column 'user_status' in 'field list'"
          ],
          "timestamp": "16:20-16:22",
          "count": 3
        }
        """;
  }
}
