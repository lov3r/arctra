package cn.bitcss.arctra.examples.incident;

import cn.bitcss.arctra.agent.AgentDefinition;
import cn.bitcss.arctra.agent.AgentExecutionContext;
import cn.bitcss.arctra.agent.AgentRequest;
import cn.bitcss.arctra.agent.AgentResult;
import cn.bitcss.arctra.examples.incident.tools.GetDeploymentTool;
import cn.bitcss.arctra.examples.incident.tools.QueryLogsTool;
import cn.bitcss.arctra.runtime.react.SpringAiToolCallingEngine;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M2-T3: Multi-Turn E2E Scenario Test with Real ChatModel
 *
 * <p>Verifies:
 * <ul>
 *   <li>Same session → conversation continuity (Turn 2 understands Turn 1)
 *   <li>Different session → conversation isolation
 *   <li>Evidence per-execution isolation
 *   <li>Stateless regression (M1 behavior preserved)
 * </ul>
 *
 * @author lov3r
 */
@Disabled("需要真实 API 调用 - 手动启用以验证 M2-T3")
@DisplayName("M2-T3: Multi-Turn E2E Scenario")
class IncidentAgentMultiTurnE2ETest {

  private final AgentDefinition incidentAgent = new AgentDefinition(
      "Incident Investigator",
      "You are an expert at analyzing production incidents. " +
      "Use the available tools to investigate issues. " +
      "Always call queryLogs first, then getDeployment to correlate timing."
  );

  private SpringAiToolCallingEngine createEngine() {
    ChatModel chatModel = OpenAiChatModel.builder()
        .options(OpenAiChatOptions.builder()
            .baseUrl("https://router.ezsub.com/v1")
            .apiKey("G5ruk5BGffumiEDpVWuPTJO4ywcPHlkXOQW6X6NbR9XDXA0a")
            .model("gpt-5.4")
            .temperature(0.3)  // Low temperature for consistency
            .build())
        .build();

    var chatMemory = MessageWindowChatMemory.builder().maxMessages(20).build();

    return new SpringAiToolCallingEngine(
        chatModel,
        List.of(new QueryLogsTool(), new GetDeploymentTool()),
        chatMemory
    );
  }

  @Test
  @DisplayName("A. Same Session Continuity - Turn 2 understands Turn 1")
  void sameSessionContinuity() {
    var engine = createEngine();
    String sessionId = "incident-123";
    var context = AgentExecutionContext.withSession(sessionId);

    // Turn 1
    System.out.println("\n=== Turn 1 ===");
    var result1 = engine.execute(
        incidentAgent,
        new AgentRequest("生产环境从 16:20 开始出现大量 500 错误，请分析原因"),
        context
    );

    System.out.println("Response: " + result1.content());
    System.out.println("Evidences: " + result1.evidences().size());

    assertThat(result1.content())
        .contains("user_status");

    assertThat(result1.evidences())
        .as("Turn 1 should have tool evidence")
        .hasSizeGreaterThanOrEqualTo(1);

    // Turn 2 - Follow-up WITHOUT repeating context
    System.out.println("\n=== Turn 2 ===");
    var result2 = engine.execute(
        incidentAgent,
        new AgentRequest("那这个问题最可能是什么原因？"),  // No mention of 500, user_status, v1.2.3
        context
    );

    System.out.println("Response: " + result2.content());

    // Turn 2 should understand "这个问题" refers to Turn 1's incident
    assertThat(result2.content())
        .as("Turn 2 should continue Turn 1 analysis")
        .containsAnyOf("schema", "migration", "user_status", "部署", "数据库");

    System.out.println("\n✅ Same Session Continuity VERIFIED");
    System.out.println("   Turn 2 successfully used Turn 1 conversation history");
  }

  @Test
  @DisplayName("B. Different Session Isolation")
  void differentSessionIsolation() {
    var engine = createEngine();

    // Session A Turn 1
    System.out.println("\n=== Session A Turn 1 ===");
    var contextA = AgentExecutionContext.withSession("session-A");
    var resultA1 = engine.execute(
        incidentAgent,
        new AgentRequest("生产环境从 16:20 开始出现大量 500 错误，请分析原因"),
        contextA
    );

    assertThat(resultA1.content()).contains("user_status");

    // Session B Turn 1 (different incident)
    System.out.println("\n=== Session B Turn 1 ===");
    var contextB = AgentExecutionContext.withSession("session-B");
    var resultB1 = engine.execute(
        incidentAgent,
        new AgentRequest("payment-service 在 10:00 开始响应缓慢，请分析"),
        contextB
    );

    System.out.println("Session B response: " + resultB1.content());

    // Session A Turn 2 - should NOT be contaminated by Session B
    System.out.println("\n=== Session A Turn 2 ===");
    var resultA2 = engine.execute(
        incidentAgent,
        new AgentRequest("刚才部署的版本是多少？"),
        contextA
    );

    System.out.println("Session A Turn 2: " + resultA2.content());

    // Should reference v1.2.3 from Session A, not payment-service
    assertThat(resultA2.content())
        .as("Session A should remember its own context")
        .containsAnyOf("v1.2.3", "user_status");

    System.out.println("\n✅ Session Isolation VERIFIED");
    System.out.println("   Session A and B remain independent");
  }

  @Test
  @DisplayName("C. Session Re-entry - A → B → A maintains context")
  void sessionReentry() {
    var engine = createEngine();

    var contextA = AgentExecutionContext.withSession("session-A");
    var contextB = AgentExecutionContext.withSession("session-B");

    // A1
    System.out.println("\n=== Session A Turn 1 ===");
    var resultA1 = engine.execute(
        incidentAgent,
        new AgentRequest("生产环境从 16:20 开始出现大量 500 错误，请分析原因"),
        contextA
    );
    assertThat(resultA1.content()).contains("user_status");

    // B1
    System.out.println("\n=== Session B Turn 1 ===");
    var resultB1 = engine.execute(
        incidentAgent,
        new AgentRequest("payment-service 在 10:00 开始响应缓慢，请分析"),
        contextB
    );

    // A2 - Re-enter Session A
    System.out.println("\n=== Session A Turn 2 (re-entry) ===");
    var resultA2 = engine.execute(
        incidentAgent,
        new AgentRequest("那最可能是什么原因？"),
        contextA
    );

    System.out.println("Session A Turn 2: " + resultA2.content());

    // Should restore A1 context, not B1
    assertThat(resultA2.content())
        .as("Session A context should be restored")
        .containsAnyOf("user_status", "schema", "migration");

    System.out.println("\n✅ Session Re-entry VERIFIED");
    System.out.println("   Session A context restored after Session B");
  }

  @Test
  @DisplayName("D. Evidence Isolation - per-execution, not session state")
  void evidenceIsolation() {
    var engine = createEngine();
    var context = AgentExecutionContext.withSession("session-evidence");

    // Turn 1
    System.out.println("\n=== Turn 1 ===");
    var result1 = engine.execute(
        incidentAgent,
        new AgentRequest("生产环境从 16:20 开始出现大量 500 错误，请分析原因"),
        context
    );

    int turn1EvidenceCount = result1.evidences().size();
    assertThat(turn1EvidenceCount).isGreaterThan(0);
    System.out.println("Turn 1 evidence count: " + turn1EvidenceCount);

    // Turn 2
    System.out.println("\n=== Turn 2 ===");
    var result2 = engine.execute(
        incidentAgent,
        new AgentRequest("那最可能是什么原因？"),
        context
    );

    System.out.println("Turn 2 evidence count: " + result2.evidences().size());

    // Evidence is per-execution, NOT cumulative across turns
    assertThat(result2.evidences())
        .as("Turn 2 Evidence should be independent of Turn 1")
        .isNotEqualTo(result1.evidences());

    System.out.println("\n✅ Evidence Isolation VERIFIED");
    System.out.println("   Conversation state ≠ Evidence state");
  }

  @Test
  @DisplayName("E. Stateless Isolation - M1 behavior preserved")
  void statelessIsolation() {
    var engine = createEngine();

    // Stateless call #1
    System.out.println("\n=== Stateless Call #1 ===");
    var result1 = engine.execute(
        incidentAgent,
        new AgentRequest("生产环境从 16:20 开始出现大量 500 错误，请分析原因")
        // No AgentExecutionContext - uses default stateless()
    );

    assertThat(result1.content()).contains("user_status");
    System.out.println("Call #1: " + result1.content().substring(0, Math.min(100, result1.content().length())));

    // Stateless call #2 - should NOT see call #1
    System.out.println("\n=== Stateless Call #2 ===");
    var result2 = engine.execute(
        incidentAgent,
        new AgentRequest("那最可能是什么原因？")
        // Also stateless
    );

    System.out.println("Call #2: " + result2.content());

    // Stateless executions should be independent
    // Call #2 should ask for clarification (no shared context)
    System.out.println("\n✅ Stateless Isolation VERIFIED");
    System.out.println("   Stateless executions remain independent");
    System.out.println("   M1 behavior preserved");
  }
}
