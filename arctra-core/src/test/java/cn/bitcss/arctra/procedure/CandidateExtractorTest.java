package cn.bitcss.arctra.procedure;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * M8-B 候选提取器测试。
 *
 * @author lov3r
 */
class CandidateExtractorTest {

  private CandidateExtractor extractor;

  @BeforeEach
  void setUp() {
    extractor = new CandidateExtractor();
  }

  @Test
  void extractFromIdempotentExecution() {
    var invocations =
        List.of(
            new ExecutionTrace.ToolInvocation(
                "getService", "hash1", "{\"serviceName\":\"api\"}", "{\"id\":\"svc-123\"}"));

    var trace = new ExecutionTrace("test-agent", "investigate-latency", invocations);

    var candidate = extractor.extract(trace, "exec-123");

    assertTrue(candidate.isPresent());
    assertEquals("test-agent", candidate.get().scope().agentName());
    assertEquals("investigate-latency", candidate.get().intentKey());
    assertEquals(1, candidate.get().proposedSteps().size());
  }

  @Test
  void rejectNonIdempotentTool() {
    var invocations =
        List.of(
            new ExecutionTrace.ToolInvocation(
                "deleteResource", "hash1", "{\"id\":\"123\"}", "{\"success\":true}"));

    var trace = new ExecutionTrace("test-agent", "cleanup", invocations);

    var candidate = extractor.extract(trace, "exec-123");

    assertTrue(candidate.isEmpty()); // 不可缓存
  }

  @Test
  void extractMultiStepProcedure() {
    var invocations =
        List.of(
            new ExecutionTrace.ToolInvocation(
                "getService", "hash1", "{\"serviceName\":\"api\"}", "{\"id\":\"svc-123\"}"),
            new ExecutionTrace.ToolInvocation(
                "queryLogs", "hash2", "{\"serviceId\":\"0.id\"}", "{\"logs\":[]}"));

    var trace = new ExecutionTrace("test-agent", "investigate", invocations);

    var candidate = extractor.extract(trace, "exec-123");

    assertTrue(candidate.isPresent());
    assertEquals(2, candidate.get().proposedSteps().size());

    // 第二步应该引用第一步的输出
    var step2 = candidate.get().proposedSteps().get(1);
    assertTrue(step2.parameterBindings().containsKey("serviceId"));
  }

  @Test
  void candidateHasProvenance() {
    var invocations =
        List.of(
            new ExecutionTrace.ToolInvocation(
                "getService", "hash1", "{\"serviceName\":\"api\"}", "{}"));

    var trace = new ExecutionTrace("test-agent", "test-intent", invocations);

    var candidate = extractor.extract(trace, "exec-999");

    assertTrue(candidate.isPresent());
    assertEquals("exec-999", candidate.get().derivedFrom());
    assertNull(candidate.get().validationOutcome()); // 待验证
  }
}
