package cn.bitcss.arctra.procedure;

import static org.junit.jupiter.api.Assertions.*;

import cn.bitcss.arctra.agent.AgentRequest;
import cn.bitcss.arctra.execution.EventType;
import cn.bitcss.arctra.execution.ExecutionLedger;
import cn.bitcss.arctra.execution.InMemoryExecutionLedger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * ProcedureCandidateExtractor 测试。
 *
 * @author lov3r
 */
class ProcedureCandidateExtractorTest {

  private ProcedureCandidateExtractor extractor;
  private ExecutionLedger ledger;
  private static final String PROCESS_ID = "test-process-1";

  @BeforeEach
  void setUp() {
    var fingerprintGenerator = new ToolSchemaFingerprintGenerator();
    extractor = new ProcedureCandidateExtractor(fingerprintGenerator);
    ledger = new InMemoryExecutionLedger();
  }

  @Test
  void extractFromSuccessfulExecution() {
    // 模拟成功执行：getService -> checkHealth
    appendToolExecuted("getService", "{\"serviceName\":\"api\"}");
    appendToolExecuted("checkHealth", "{\"serviceId\":\"svc-123\"}");

    var request = new AgentRequest("investigate latency");
    var result = extractor.extract(ledger, PROCESS_ID, request, "test-agent");

    assertTrue(result.isPresent());

    var candidate = result.get();
    assertEquals("test-agent", candidate.intentKey());
    assertEquals(2, candidate.proposedSteps().size());

    // 验证第一步
    var step0 = candidate.proposedSteps().get(0);
    assertEquals(0, step0.stepIndex());
    assertEquals("getService", step0.toolName());
    assertTrue(step0.parameterBindings().containsKey("serviceName"));
    assertEquals(BindingSource.INPUT, step0.parameterBindings().get("serviceName").source());

    // 验证第二步
    var step1 = candidate.proposedSteps().get(1);
    assertEquals(1, step1.stepIndex());
    assertEquals("checkHealth", step1.toolName());
    assertTrue(step1.parameterBindings().containsKey("serviceId"));
  }

  @Test
  void returnEmptyWhenNoToolExecutedEvents() {
    // 只有 PROCESS_STARTED，没有 TOOL_EXECUTED
    ledger.append(PROCESS_ID, EventType.PROCESS_STARTED, null, null);

    var request = new AgentRequest("test");
    var result = extractor.extract(ledger, PROCESS_ID, request, "test-agent");

    assertTrue(result.isEmpty());
  }

  @Test
  void returnEmptyWhenLedgerIsEmpty() {
    var request = new AgentRequest("test");
    var result = extractor.extract(ledger, PROCESS_ID, request, "test-agent");

    assertTrue(result.isEmpty());
  }

  @Test
  void extractWithMultipleParameters() {
    appendToolExecuted(
        "queryMetrics",
        "{\"serviceName\":\"api\",\"startTime\":\"2024-01-01\",\"endTime\":\"2024-01-02\"}");

    var request = new AgentRequest("query metrics");
    var result = extractor.extract(ledger, PROCESS_ID, request, "test-agent");

    assertTrue(result.isPresent());

    var candidate = result.get();
    var step = candidate.proposedSteps().get(0);

    // 验证所有参数都被推断为 INPUT 绑定
    assertEquals(3, step.parameterBindings().size());
    assertTrue(step.parameterBindings().containsKey("serviceName"));
    assertTrue(step.parameterBindings().containsKey("startTime"));
    assertTrue(step.parameterBindings().containsKey("endTime"));
  }

  @Test
  void generateUniqueProcedureId() {
    appendToolExecuted("tool1", "{}");

    var request = new AgentRequest("test");
    var result1 = extractor.extract(ledger, PROCESS_ID, request, "agent-1");

    // 清空 ledger，重新添加
    ledger = new InMemoryExecutionLedger();
    appendToolExecuted("tool1", "{}");
    var result2 = extractor.extract(ledger, PROCESS_ID, request, "agent-2");

    assertTrue(result1.isPresent());
    assertTrue(result2.isPresent());

    // 不同 agent 生成不同 candidateId
    assertNotEquals(result1.get().candidateId(), result2.get().candidateId());
  }

  @Test
  void useScopeFromAgentName() {
    appendToolExecuted("tool1", "{}");

    var request = new AgentRequest("test");
    var result = extractor.extract(ledger, PROCESS_ID, request, "test-agent");

    assertTrue(result.isPresent());

    var candidate = result.get();
    assertEquals(ProcedureScope.forAgent("test-agent"), candidate.scope());
  }

  @Test
  void preserveExecutionOrder() {
    // 添加多个工具调用
    appendToolExecuted("tool1", "{}");
    appendToolExecuted("tool2", "{}");
    appendToolExecuted("tool3", "{}");

    var request = new AgentRequest("test");
    var result = extractor.extract(ledger, PROCESS_ID, request, "test-agent");

    assertTrue(result.isPresent());

    var candidate = result.get();
    assertEquals(3, candidate.proposedSteps().size());

    // 验证顺序
    assertEquals("tool1", candidate.proposedSteps().get(0).toolName());
    assertEquals("tool2", candidate.proposedSteps().get(1).toolName());
    assertEquals("tool3", candidate.proposedSteps().get(2).toolName());
  }

  private void appendToolExecuted(String toolName, String arguments) {
    String payload = String.format(
        "{\"operationId\":\"op-1\",\"toolCallId\":\"call-1\",\"toolName\":\"%s\",\"arguments\":\"%s\",\"result\":\"success\"}",
        toolName,
        arguments.replace("\"", "\\\""));

    ledger.append(PROCESS_ID, EventType.TOOL_EXECUTED, null, payload);
  }
}
