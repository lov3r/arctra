package cn.bitcss.arctra.procedure;

import static org.junit.jupiter.api.Assertions.*;

import cn.bitcss.arctra.governance.GovernanceDecision;
import cn.bitcss.arctra.governance.ToolGovernancePolicy;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * M8-D 过程执行协调器测试。
 *
 * @author lov3r
 */
class ProcedureExecutionCoordinatorTest {

  private ProcedureExecutionCoordinator coordinator;
  private OperationGovernanceEvaluator allowAllEvaluator;
  private ParameterBindingResolver resolver;

  @BeforeEach
  void setUp() {
    allowAllEvaluator = new OperationGovernanceEvaluator(ToolGovernancePolicy.allowAll());
    resolver = new ParameterBindingResolver();
    coordinator = new ProcedureExecutionCoordinator(allowAllEvaluator, resolver);
  }

  @Test
  void prepareFirstStepWithAllowPolicy() throws Exception {
    var procedure = createSimpleProcedure();
    var executionState =
        ProcedureExecutionState.initial(
            procedure.procedureId(), procedure.revision(), Map.of("serviceName", "api"));

    var result = coordinator.prepareNextStep(procedure, executionState);

    assertTrue(result.isAllowed());
    assertFalse(result.isCompleted());
    assertFalse(result.requiresApproval());

    var pendingCall = result.pendingCall();
    assertEquals("getService", pendingCall.toolName());
    assertTrue(pendingCall.arguments().contains("\"serviceName\":\"api\""));
  }

  @Test
  void detectCompletionWhenAllStepsFinished() throws Exception {
    var procedure = createSimpleProcedure();
    var executionState =
        new ProcedureExecutionState(
            procedure.procedureId(),
            procedure.revision(),
            1, // currentStepIndex = 1 (过程只有 1 步，索引 0)
            Map.of("serviceName", "api"),
            Map.of());

    var result = coordinator.prepareNextStep(procedure, executionState);

    assertTrue(result.isCompleted());
  }

  @Test
  void requireApprovalWhenPolicyRequiresIt() throws Exception {
    // 需要批准的策略
    var requireApprovalPolicy =
        new OperationGovernanceEvaluator(
            (toolName, args, ctx) -> GovernanceDecision.REQUIRE_APPROVAL);
    coordinator = new ProcedureExecutionCoordinator(requireApprovalPolicy, resolver);

    var procedure = createSimpleProcedure();
    var executionState =
        ProcedureExecutionState.initial(
            procedure.procedureId(), procedure.revision(), Map.of("serviceName", "api"));

    var result = coordinator.prepareNextStep(procedure, executionState);

    assertTrue(result.requiresApproval());
    assertFalse(result.isCompleted());
    assertFalse(result.isAllowed());
  }

  @Test
  void throwExceptionWhenPolicyDenies() {
    // 拒绝策略
    var denyPolicy =
        new OperationGovernanceEvaluator((toolName, args, ctx) -> GovernanceDecision.DENY);
    coordinator = new ProcedureExecutionCoordinator(denyPolicy, resolver);

    var procedure = createSimpleProcedure();
    var executionState =
        ProcedureExecutionState.initial(
            procedure.procedureId(), procedure.revision(), Map.of("serviceName", "api"));

    var ex =
        assertThrows(
            ProcedureGovernanceException.class,
            () -> coordinator.prepareNextStep(procedure, executionState));

    assertEquals(procedure.procedureId(), ex.procedureId());
    assertEquals(procedure.revision(), ex.revision());
    assertEquals(0, ex.stepIndex());
    assertEquals("getService", ex.toolName());
  }

  @Test
  void captureStepOutput() {
    var step = createStepWithOutputExtraction();
    String toolResult = "{\"id\":\"svc-123\",\"name\":\"api\",\"status\":\"running\"}";

    var output = coordinator.captureStepOutput(step, toolResult);

    assertEquals("svc-123", output.getField("id"));
  }

  @Test
  void rejectProcedureIdMismatch() {
    var procedure = createSimpleProcedure();
    var executionState =
        ProcedureExecutionState.initial(
            "different-proc-id", procedure.revision(), Map.of("serviceName", "api"));

    assertThrows(
        IllegalArgumentException.class, () -> coordinator.prepareNextStep(procedure, executionState));
  }

  @Test
  void rejectRevisionMismatch() {
    var procedure = createSimpleProcedure();
    var executionState =
        ProcedureExecutionState.initial(
            procedure.procedureId(), 999, Map.of("serviceName", "api"));

    assertThrows(
        IllegalArgumentException.class, () -> coordinator.prepareNextStep(procedure, executionState));
  }

  private ReusableProcedure createSimpleProcedure() {
    var fingerprint = new ToolCompatibilityFingerprint("getService", "hash1");
    var bindings = Map.of("serviceName", new ParameterBinding(BindingSource.INPUT, "serviceName"));
    var step = new ProcedureStep(0, "getService", fingerprint, bindings, List.of());

    return new ReusableProcedure(
        "test-proc",
        1,
        ProcedureScope.forAgent("test-agent"),
        "investigate-latency",
        List.of(step),
        ProcedureStatus.VALID,
        java.time.Instant.now(),
        "test-creator");
  }

  private ProcedureStep createStepWithOutputExtraction() {
    var fingerprint = new ToolCompatibilityFingerprint("getService", "hash1");
    var bindings = Map.of("serviceName", new ParameterBinding(BindingSource.INPUT, "serviceName"));
    var outputs = List.of(new OutputExtraction("id", "/id"));

    return new ProcedureStep(0, "getService", fingerprint, bindings, outputs);
  }
}
