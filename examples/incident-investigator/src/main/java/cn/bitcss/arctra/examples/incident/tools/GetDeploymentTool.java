package cn.bitcss.arctra.examples.incident.tools;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * Mock tool for querying deployment information (Incident Scenario fixture).
 *
 * <p>Returns fixed mock data for M1 E2E testing. This is a scenario-specific fixture, not a
 * framework capability.
 *
 * @author lov3r
 */
public class GetDeploymentTool implements ToolCallback {

  @Override
  public ToolDefinition getToolDefinition() {
    return ToolDefinition.builder()
        .name("getDeployment")
        .description("Get recent deployment information to correlate with incidents")
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
    // Return fixed mock deployment info for Incident Scenario
    // Version: v1.2.3, Time: 16:18, Change: Added user_status field
    return """
        {
          "version": "v1.2.3",
          "deployedAt": "16:18",
          "changes": [
            "Added user_status field to User entity",
            "Updated UserService to handle user status"
          ],
          "author": "dev-team"
        }
        """;
  }
}
