package cn.bitcss.arctra.examples.incident;

import static org.assertj.core.api.Assertions.assertThat;

import cn.bitcss.arctra.agent.AgentDefinition;
import cn.bitcss.arctra.agent.AgentRequest;
import cn.bitcss.arctra.agent.AgentResult;
import cn.bitcss.arctra.examples.incident.tools.GetDeploymentTool;
import cn.bitcss.arctra.examples.incident.tools.QueryLogsTool;
import cn.bitcss.arctra.runtime.react.SpringAiToolCallingEngine;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.setup.OpenAiSetup;

/**
 * E2E test with real OpenAI API (via proxy).
 *
 * <p>This test uses a real ChatModel to validate the complete vertical slice with actual LLM
 * reasoning.
 *
 * <p><b>Note:</b> This test is disabled by default because it requires network access and API
 * costs.
 *
 * <p>To run this test, remove the @Disabled annotation and run:
 * {@code ./mvnw test -pl examples/incident-investigator -Dtest=IncidentAgentRealE2ETest}
 *
 * @author lov3r
 */
@Disabled("Requires network access and API costs - enable manually for real validation")
class IncidentAgentRealE2ETest {

  @Test
  void should_analyze_incident_with_real_openai() {
    // Arrange: Create tools
    var queryLogsTool = new QueryLogsTool();
    var getDeploymentTool = new GetDeploymentTool();

    // Arrange: Create real OpenAI ChatModel (via proxy)
    var baseUrl = "https://router.ezsub.com/v1";
    var apiKey = "G5ruk5BGffumiEDpVWuPTJO4ywcPHlkXOQW6X6NbR9XDXA0a";

    var openAiClient = OpenAiSetup.setupSyncClient(
        baseUrl,           // baseUrl
        null,              // azureEndpoint
        null,              // credential (will use apiKey)
        apiKey,            // apiKey
        null,              // azureApiVersion
        null,              // azureDeploymentName
        false,             // isAzure
        false,             // useMicrosoftFoundry
        null,              // userAgent
        java.time.Duration.ofSeconds(60), // timeout
        3,                 // maxRetries
        null,              // proxy
        null,              // headers
        io.micrometer.observation.ObservationRegistry.NOOP, // observationRegistry
        null,              // meterRegistry
        List.of()          // httpClientBuilderCustomizers
    );

    var chatModel =
        OpenAiChatModel.builder()
            .openAiClient(openAiClient)
            .options(
                OpenAiChatOptions.builder()
                    .model("gpt-5.4")
                    .temperature(0.3)
                    .build())
            .build();

    // Arrange: Create Engine
    var engine =
        new SpringAiToolCallingEngine(chatModel, List.of(queryLogsTool, getDeploymentTool));

    // Arrange: Create AgentDefinition
    var agentDefinition =
        new AgentDefinition(
            "Incident Investigator",
            "You are an expert at analyzing production incidents. "
                + "When investigating errors, you should: "
                + "1. Check application logs to understand the error "
                + "2. Check recent deployments to identify potential causes "
                + "3. Correlate the timing of errors with deployment changes "
                + "4. Provide a clear diagnosis of the root cause");

    // Arrange: Create AgentRequest (Incident Question)
    var request = new AgentRequest("生产环境从 16:20 开始出现大量 500 错误，请分析原因");

    // Act: Execute with real LLM
    AgentResult result = engine.execute(agentDefinition, request);

    // Assert: Content contains key analysis
    var content = result.content();
    System.out.println("=== Agent Response ===");
    System.out.println(content);
    System.out.println();

    assertThat(content)
        .as("Response should mention user_status column error")
        .containsIgnoringCase("user_status");

    assertThat(content)
        .as("Response should mention deployment version or time")
        .satisfiesAnyOf(
            c -> assertThat(c).containsIgnoringCase("v1.2.3"),
            c -> assertThat(c).containsIgnoringCase("16:18"));

    assertThat(content)
        .as("Response should mention schema-related issue")
        .satisfiesAnyOf(
            c -> assertThat(c).containsIgnoringCase("schema"),
            c -> assertThat(c).containsIgnoringCase("migration"),
            c -> assertThat(c).containsIgnoringCase("database"),
            c -> assertThat(c).containsIgnoringCase("column"));

    // Assert: Evidences captured
    var evidences = result.evidences();
    System.out.println("=== Evidences ===");
    evidences.forEach(
        e -> System.out.println("Source: " + e.source() + "\nContent: " + e.content() + "\n"));

    assertThat(evidences)
        .as("Should have captured tool invocations")
        .hasSizeGreaterThanOrEqualTo(2);

    assertThat(evidences)
        .as("Should have evidence from queryLogs")
        .anyMatch(e -> e.source().equals("tool:queryLogs"));

    assertThat(evidences)
        .as("Should have evidence from getDeployment")
        .anyMatch(e -> e.source().equals("tool:getDeployment"));

    // Assert: Evidence content contains mock data
    var queryLogsEvidence =
        evidences.stream()
            .filter(e -> e.source().equals("tool:queryLogs"))
            .findFirst()
            .orElseThrow();

    assertThat(queryLogsEvidence.content()).contains("SQLException");
    assertThat(queryLogsEvidence.content()).contains("user_status");

    var deploymentEvidence =
        evidences.stream()
            .filter(e -> e.source().equals("tool:getDeployment"))
            .findFirst()
            .orElseThrow();

    assertThat(deploymentEvidence.content()).contains("v1.2.3");
    assertThat(deploymentEvidence.content()).contains("16:18");
  }
}
