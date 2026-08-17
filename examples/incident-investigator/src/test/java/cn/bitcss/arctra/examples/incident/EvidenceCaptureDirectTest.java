package cn.bitcss.arctra.examples.incident;

import static org.assertj.core.api.Assertions.assertThat;

import cn.bitcss.arctra.evidence.Evidence;
import cn.bitcss.arctra.examples.incident.tools.GetDeploymentTool;
import cn.bitcss.arctra.examples.incident.tools.QueryLogsTool;
import cn.bitcss.arctra.runtime.react.EvidenceCapturingToolCallback;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Direct test of EvidenceCapturingToolCallback to validate evidence capture mechanism.
 *
 * <p>This test bypasses SpringAiToolCallingEngine and directly tests that:
 * <ul>
 *   <li>EvidenceCapturingToolCallback correctly wraps tools
 *   <li>Tool invocations are captured as Evidence
 *   <li>Evidence contains expected tool results
 * </ul>
 *
 * <p>This validates the core evidence capture mechanism works, even though we haven't
 * validated the complete Spring AI tool calling loop integration.
 *
 * @author lov3r
 */
class EvidenceCaptureDirectTest {

  @Test
  void should_capture_evidence_when_tools_are_invoked_directly() {
    // Arrange: Create tools
    var queryLogsTool = new QueryLogsTool();
    var getDeploymentTool = new GetDeploymentTool();

    // Arrange: Create evidence collection list
    List<Evidence> evidences = new ArrayList<>();

    // Arrange: Wrap tools with evidence capture
    var wrappedQueryLogs = new EvidenceCapturingToolCallback(queryLogsTool, evidences);
    var wrappedGetDeployment = new EvidenceCapturingToolCallback(getDeploymentTool, evidences);

    // Act: Invoke wrapped tools directly
    System.out.println("=== Invoking Wrapped Tools Directly ===");

    System.out.println("\nInvoking queryLogs...");
    var logsResult = wrappedQueryLogs.call("{}");
    System.out.println("Result: " + logsResult);

    System.out.println("\nInvoking getDeployment...");
    var deploymentResult = wrappedGetDeployment.call("{}");
    System.out.println("Result: " + deploymentResult);

    // Assert: Evidence captured from BOTH tool invocations
    System.out.println("\n=== Evidence Captured ===");
    evidences.forEach(e ->
        System.out.println("Source: " + e.source() + "\nContent: " + e.content() + "\n")
    );

    assertThat(evidences)
        .as("Should have captured evidence from both tools")
        .hasSize(2);

    // Assert: Evidence from queryLogs
    var queryLogsEvidence =
        evidences.stream()
            .filter(e -> e.source().equals("tool:queryLogs"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("queryLogs evidence not found"));

    assertThat(queryLogsEvidence.content()).contains("SQLException");
    assertThat(queryLogsEvidence.content()).contains("user_status");

    // Assert: Evidence from getDeployment
    var deploymentEvidence =
        evidences.stream()
            .filter(e -> e.source().equals("tool:getDeployment"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("getDeployment evidence not found"));

    assertThat(deploymentEvidence.content()).contains("v1.2.3");
    assertThat(deploymentEvidence.content()).contains("16:18");

    System.out.println("\n✅ Evidence Capture Mechanism Validated!");
    System.out.println("   - EvidenceCapturingToolCallback works correctly");
    System.out.println("   - Tool invocations are captured as Evidence");
    System.out.println("   - Evidence contains expected tool results");
  }
}
