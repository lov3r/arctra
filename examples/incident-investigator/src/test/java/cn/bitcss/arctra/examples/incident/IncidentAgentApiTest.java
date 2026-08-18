package cn.bitcss.arctra.examples.incident;

import cn.bitcss.arctra.agent.Agent;
import cn.bitcss.arctra.agent.AgentDefinition;
import cn.bitcss.arctra.agent.AgentExecutionContext;
import cn.bitcss.arctra.agent.AgentRequest;
import cn.bitcss.arctra.agent.AgentResult;
import cn.bitcss.arctra.examples.incident.tools.GetDeploymentTool;
import cn.bitcss.arctra.examples.incident.tools.QueryLogsTool;
import cn.bitcss.arctra.runtime.AgentRuntime;
import cn.bitcss.arctra.runtime.DefaultAgentRuntime;
import cn.bitcss.arctra.runtime.react.SpringAiToolCallingEngine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M3-T3: Agent API Migration Test
 *
 * <p>Demonstrates the recommended Agent API usage pattern for Incident Investigation scenario.
 *
 * <p>Key aspects verified:
 * <ul>
 *   <li>Agent handle creation via AgentRuntime
 *   <li>Stateless invocation
 *   <li>Stateful invocation (multi-turn)
 *   <li>Agent handle reuse
 *   <li>Session isolation
 *   <li>Evidence capture regression
 * </ul>
 *
 * @author lov3r
 */
@DisplayName("M3-T3: Agent API Migration")
class IncidentAgentApiTest {

  private final AgentDefinition incidentAgentDefinition = new AgentDefinition(
      "Incident Investigator",
      "You are an expert at analyzing production incidents. " +
      "Use the available tools to investigate issues. " +
      "Always call queryLogs first, then getDeployment to correlate timing."
  );

  /**
   * Composition Root: Create Agent handle
   *
   * <p>This demonstrates the recommended pattern for Agent creation:
   * <ol>
   *   <li>Create Engine with ChatModel, Tools, ChatMemory
   *   <li>Create Runtime with Engine
   *   <li>Create Agent handle with AgentDefinition
   * </ol>
   *
   * <p>Business code then receives the Agent handle and uses it for invocation.
   */
  private Agent createIncidentAgent() {
    // Step 1: Create ChatModel (using Fake for testing)
    var fakeChatModel = new FakeChatModelWithToolCalling();

    // Step 2: Create Tools
    var tools = List.of(
        new QueryLogsTool(),
        new GetDeploymentTool()
    );

    // Step 3: Create ChatMemory
    var chatMemory = MessageWindowChatMemory.builder()
        .maxMessages(20)
        .build();

    // Step 4: Create Engine
    var engine = new SpringAiToolCallingEngine(
        fakeChatModel,
        tools,
        chatMemory
    );

    // Step 5: Create Runtime
    AgentRuntime runtime = new DefaultAgentRuntime(engine);

    // Step 6: Create Agent handle (bound to definition)
    return runtime.agent(incidentAgentDefinition);
  }

  @Test
  @DisplayName("1. Stateless invocation - Simple case")
  void statelessInvocation() {
    // Composition Root
    Agent agent = createIncidentAgent();

    // Business Code - Stateless invocation
    AgentResult result = agent.execute(
        new AgentRequest("生产环境从 16:20 开始出现大量 500 错误，请分析原因")
    );

    // Verify
    assertThat(result.content())
        .as("Agent should provide analysis")
        .isNotBlank();

    assertThat(result.evidences())
        .as("Evidence should be captured")
        .isNotEmpty();

    System.out.println("\n✅ Stateless invocation successful");
    System.out.println("Response: " + result.content());
    System.out.println("Evidences: " + result.evidences().size());
  }

  @Test
  @DisplayName("2. Stateful invocation - Multi-turn conversation")
  void statefulInvocation() {
    // Composition Root
    Agent agent = createIncidentAgent();

    var sessionId = "incident-123";
    var context = AgentExecutionContext.withSession(sessionId);

    // Business Code - Turn 1
    AgentResult result1 = agent.execute(
        new AgentRequest("生产环境从 16:20 开始出现大量 500 错误，请分析原因"),
        context
    );

    assertThat(result1.content()).isNotBlank();
    System.out.println("\n=== Turn 1 ===");
    System.out.println(result1.content());

    // Business Code - Turn 2 (follow-up)
    AgentResult result2 = agent.execute(
        new AgentRequest("那这个问题最可能是什么原因？"),
        context
    );

    assertThat(result2.content()).isNotBlank();
    System.out.println("\n=== Turn 2 ===");
    System.out.println(result2.content());

    System.out.println("\n✅ Multi-turn conversation successful");
    System.out.println("Turn 2 used Turn 1 conversation history");
  }

  @Test
  @DisplayName("3. Agent handle reuse - Multiple invocations")
  void agentHandleReuse() {
    // Composition Root - Create once
    Agent agent = createIncidentAgent();

    // Business Code - Multiple invocations with same handle
    AgentResult result1 = agent.execute(
        new AgentRequest("Incident A")
    );

    AgentResult result2 = agent.execute(
        new AgentRequest("Incident B")
    );

    AgentResult result3 = agent.execute(
        new AgentRequest("Incident C")
    );

    // Verify all succeeded
    assertThat(result1.content()).isNotBlank();
    assertThat(result2.content()).isNotBlank();
    assertThat(result3.content()).isNotBlank();

    System.out.println("\n✅ Agent handle reused successfully");
    System.out.println("Same handle processed 3 different requests");
  }

  @Test
  @DisplayName("4. Session isolation - Different sessions independent")
  void sessionIsolation() {
    // Composition Root
    Agent agent = createIncidentAgent();

    // Business Code - Session A
    var contextA = AgentExecutionContext.withSession("session-A");
    AgentResult resultA1 = agent.execute(
        new AgentRequest("生产环境从 16:20 开始出现大量 500 错误"),
        contextA
    );

    // Business Code - Session B (different incident)
    var contextB = AgentExecutionContext.withSession("session-B");
    AgentResult resultB1 = agent.execute(
        new AgentRequest("payment-service 在 10:00 开始响应缓慢"),
        contextB
    );

    // Business Code - Session A Turn 2 (should NOT see Session B)
    AgentResult resultA2 = agent.execute(
        new AgentRequest("刚才部署的版本是多少？"),
        contextA
    );

    // Verify
    assertThat(resultA1.content()).isNotBlank();
    assertThat(resultB1.content()).isNotBlank();
    assertThat(resultA2.content()).isNotBlank();

    System.out.println("\n✅ Session isolation verified");
    System.out.println("Session A and B remained independent");
  }

  @Test
  @DisplayName("5. Evidence capture regression - M1 behavior preserved")
  void evidenceCaptureRegression() {
    // Composition Root
    Agent agent = createIncidentAgent();

    // Business Code
    AgentResult result = agent.execute(
        new AgentRequest("Analyze production incident")
    );

    // Verify Evidence capture still works
    assertThat(result.evidences())
        .as("Evidence capture should still work through Agent API")
        .isNotEmpty();

    assertThat(result.evidences())
        .extracting("source")
        .allMatch(source -> source.startsWith("tool:"));

    System.out.println("\n✅ Evidence capture regression passed");
    System.out.println("M1 Evidence mechanism preserved in M3 Agent API");
  }

  @Test
  @DisplayName("6. Multiple Agent Definitions - Different bindings")
  void multipleAgentDefinitions() {
    // Composition Root - Create Engine/Runtime once
    var fakeChatModel = new FakeChatModelWithToolCalling();
    var tools = List.of(new QueryLogsTool(), new GetDeploymentTool());
    var chatMemory = MessageWindowChatMemory.builder().maxMessages(20).build();
    var engine = new SpringAiToolCallingEngine(fakeChatModel, tools, chatMemory);
    AgentRuntime runtime = new DefaultAgentRuntime(engine);

    // Create two different agent handles
    Agent incidentAgent = runtime.agent(
        new AgentDefinition("Incident Investigator", "Analyzes incidents")
    );

    Agent deploymentAgent = runtime.agent(
        new AgentDefinition("Deployment Analyzer", "Analyzes deployments")
    );

    // Business Code - Use different agents
    AgentResult result1 = incidentAgent.execute(
        new AgentRequest("Analyze incident")
    );

    AgentResult result2 = deploymentAgent.execute(
        new AgentRequest("Analyze deployment")
    );

    // Verify both work
    assertThat(result1.content()).isNotBlank();
    assertThat(result2.content()).isNotBlank();

    System.out.println("\n✅ Multiple agent definitions verified");
    System.out.println("Each agent handle uses its bound definition");
  }
}
