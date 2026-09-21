package cn.bitcss.arctra.procedure;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import cn.bitcss.arctra.checkpoint.PendingToolCall;
import cn.bitcss.arctra.governance.GovernanceDecision;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * ProcedureExecutionHandler 测试。
 *
 * @author lov3r
 */
class ProcedureExecutionHandlerTest {

  private ProcedureExecutionCoordinator coordinator;
  private OperationGovernanceEvaluator governanceEvaluator;
  private ParameterBindingResolver bindingResolver;
  private ProcedureExecutionHandler handler;

  @BeforeEach
  void setUp() {
    governanceEvaluator = mock(OperationGovernanceEvaluator.class);
    bindingResolver = mock(ParameterBindingResolver.class);
    coordinator = new ProcedureExecutionCoordinator(governanceEvaluator, bindingResolver);
    handler = new ProcedureExecutionHandler(coordinator);
  }

  @Test
  void executeAllowedStep() throws Exception {
    // 创建简单过程（1 步）
    var procedure = createSimpleProcedure("proc-1", 1);
    var executionState = ProcedureExecutionState.initial("proc-1", 1, Map.of("input", "value"));

    // Mock 绑定解析和治理
    when(bindingResolver.resolve(any(), any())).thenReturn(Map.of("param", "value"));
    when(governanceEvaluator.evaluate(any(), any())).thenReturn(GovernanceDecision.ALLOW);

    // 执行下一步
    var result = handler.executeNextStep(procedure, executionState);

    assertFalse(result.isCompleted());
    assertFalse(result.requiresApproval());
    assertTrue(result.isAllowed());
    assertNotNull(result.pendingCall());
    assertEquals("testTool", result.pendingCall().toolName());
  }

  @Test
  void executeRequiresApprovalStep() throws Exception {
    var procedure = createSimpleProcedure("proc-1", 1);
    var executionState = ProcedureExecutionState.initial("proc-1", 1, Map.of());

    when(bindingResolver.resolve(any(), any())).thenReturn(Map.of());
    when(governanceEvaluator.evaluate(any(), any()))
        .thenReturn(GovernanceDecision.REQUIRE_APPROVAL);

    var result = handler.executeNextStep(procedure, executionState);

    assertFalse(result.isCompleted());
    assertTrue(result.requiresApproval());
    assertFalse(result.isAllowed());
    assertNotNull(result.pendingCall());
  }

  @Test
  void throwsOnDeniedStep() throws Exception {
    var procedure = createSimpleProcedure("proc-1", 1);
    var executionState = ProcedureExecutionState.initial("proc-1", 1, Map.of());

    when(bindingResolver.resolve(any(), any())).thenReturn(Map.of());
    when(governanceEvaluator.evaluate(any(), any())).thenReturn(GovernanceDecision.DENY);

    assertThrows(
        ProcedureGovernanceException.class,
        () -> handler.executeNextStep(procedure, executionState));
  }

  @Test
  void detectsCompletion() throws Exception {
    var procedure = createSimpleProcedure("proc-1", 1);
    // 已完成所有步骤
    var executionState = new ProcedureExecutionState("proc-1", 1, 1, Map.of(), Map.of());

    var result = handler.executeNextStep(procedure, executionState);

    assertTrue(result.isCompleted());
    assertFalse(result.isAllowed());
    assertFalse(result.requiresApproval());
  }

  @Test
  void advancesStateAfterSuccess() throws Exception {
    var executionState = ProcedureExecutionState.initial("proc-1", 1, Map.of());
    var step = createSimpleStep(0);
    var toolResult = "success result";

    var newState = handler.advanceAfterSuccess(executionState, step, toolResult);

    assertEquals(1, newState.currentStepIndex()); // 推进到下一步
    assertTrue(newState.capturedStepOutputs().containsKey(0)); // 捕获了输出
  }

  @Test
  void validatesProcedureIdMismatch() throws Exception {
    var procedure = createSimpleProcedure("proc-1", 1);
    var executionState = ProcedureExecutionState.initial("proc-2", 1, Map.of()); // 不同 ID

    assertThrows(
        IllegalArgumentException.class, () -> handler.executeNextStep(procedure, executionState));
  }

  @Test
  void validatesProcedureRevisionMismatch() throws Exception {
    var procedure = createSimpleProcedure("proc-1", 1);
    var executionState = ProcedureExecutionState.initial("proc-1", 2, Map.of()); // 不同修订

    assertThrows(
        IllegalArgumentException.class, () -> handler.executeNextStep(procedure, executionState));
  }

  private ReusableProcedure createSimpleProcedure(String procedureId, int revision) {
    var scope = ProcedureScope.forAgent("test-agent");
    var fingerprint = new ToolCompatibilityFingerprint("testTool", "hash1");
    var step = new ProcedureStep(0, "testTool", fingerprint, Map.of(), List.of());

    return new ReusableProcedure(
        procedureId, revision, scope, "test-agent", List.of(step), ProcedureStatus.VALID,
        Instant.now(), null);
  }

  private ProcedureStep createSimpleStep(int stepIndex) {
    var fingerprint = new ToolCompatibilityFingerprint("testTool", "hash1");
    return new ProcedureStep(stepIndex, "testTool", fingerprint, Map.of(), List.of());
  }
}
