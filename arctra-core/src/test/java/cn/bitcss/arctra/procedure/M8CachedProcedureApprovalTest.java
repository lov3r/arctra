package cn.bitcss.arctra.procedure;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import cn.bitcss.arctra.checkpoint.ContinuationDisposition;
import cn.bitcss.arctra.checkpoint.PendingToolCall;
import cn.bitcss.arctra.checkpoint.SuspensionCheckpoint;
import cn.bitcss.arctra.governance.GovernanceDecision;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * M8-Phase 3.4: Cached Procedure Approval Tests.
 *
 * <p>Proves that {@link ProcedureExecutionHandler} correctly handles REQUIRE_APPROVAL during cached
 * procedure execution, producing checkpoints with proper procedureState for suspension and resume.
 *
 * @author lov3r
 * @since M8-Integration-Phase3.4
 */
@DisplayName("M8-Phase 3.4: Cached Procedure Approval")
class M8CachedProcedureApprovalTest {

  private ProcedureExecutionCoordinator coordinator;
  private OperationGovernanceEvaluator governanceEvaluator;
  private ParameterBindingResolver bindingResolver;
  private ReusableProcedureStore procedureStore;
  private ProcedureExecutionHandler handler;

  @BeforeEach
  void setUp() {
    governanceEvaluator = mock(OperationGovernanceEvaluator.class);
    bindingResolver = mock(ParameterBindingResolver.class);
    procedureStore = mock(ReusableProcedureStore.class);
    coordinator = new ProcedureExecutionCoordinator(governanceEvaluator, bindingResolver);
    handler = new ProcedureExecutionHandler(coordinator, procedureStore);
  }

  @Test
  @DisplayName("3.4.1: executeNextStep returns requiresApproval=true for REQUIRE_APPROVAL step")
  void executeNextStep_requiresApproval() throws Exception {
    // Given
    var procedure = createSimpleProcedure("proc-1", 1);
    var executionState = ProcedureExecutionState.initial("proc-1", 1, Map.of("input", "value"));

    // Mock governance to require approval
    when(bindingResolver.resolve(any(), any())).thenReturn(Map.of("param", "value"));
    when(governanceEvaluator.evaluate(any(), any()))
        .thenReturn(GovernanceDecision.REQUIRE_APPROVAL);

    // When
    var result = handler.executeNextStep(procedure, executionState);

    // Then
    assertTrue(
        result.requiresApproval(),
        "Result should indicate approval required for REQUIRE_APPROVAL governance");
    assertFalse(result.isAllowed(), "Result should not be allowed");
    assertFalse(result.isCompleted(), "Result should not be completed");
    assertNotNull(result.pendingCall(), "Result should have pending call");
    assertEquals("testTool", result.pendingCall().toolName(), "Pending call should be testTool");
  }

  @Test
  @DisplayName("3.4.2: advanceAfterSuccess correctly advances state after approved step execution")
  void advanceAfterSuccess_afterApproval() throws Exception {
    // Given
    var executionState = ProcedureExecutionState.initial("proc-1", 1, Map.of("input", "value"));
    var step = createSimpleStep(0);
    var toolResult = "{\"result\": \"success result\"}";

    // When
    var newState = handler.advanceAfterSuccess(executionState, step, toolResult);

    // Then
    assertEquals(1, newState.currentStepIndex(), "Should advance to next step (index 1)");
    assertTrue(
        newState.capturedStepOutputs().containsKey(0), "Should capture step 0 output");
    assertNotNull(newState.capturedStepOutputs().get(0), "Should capture tool result");
  }

  @Test
  @DisplayName("3.4.3: Checkpoint with procedureState enables cached execution resume")
  void checkpoint_withProcedureState_enablesResume() {
    // Given - create checkpoint with procedure state (simulating suspension during cached execution)
    var procState = ProcedureExecutionState.initial("proc-1", 1, Map.of("input", "value"));
    var pendingCall = new PendingToolCall("op-1", "tc-1", "testTool", "{}");

    // When - create checkpoint with procedure state
    var checkpoint =
        new SuspensionCheckpoint(
            SuspensionCheckpoint.CURRENT_SCHEMA_VERSION,
            "process-1",
            1L,
            "binding-key",
            "session-1",
            ContinuationDisposition.WAITING_FOR_SIGNAL,
            List.of(pendingCall),
            List.of(),
            "epoch-1",
            procState); // M8: procedure state present

    // Then
    assertNotNull(checkpoint.procedureState(), "Checkpoint should contain procedure state");
    assertEquals("proc-1", checkpoint.procedureState().procedureId(), "Should have correct procedure ID");
    assertEquals(1, checkpoint.procedureState().procedureRevision(), "Should have correct revision");
    assertEquals(0, checkpoint.procedureState().currentStepIndex(), "Should be at step 0");
    assertEquals(
        ContinuationDisposition.WAITING_FOR_SIGNAL,
        checkpoint.disposition(),
        "Disposition should be WAITING_FOR_SIGNAL for approval");
  }

  @Test
  @DisplayName("3.4.4: Multi-step procedure suspends at each REQUIRE_APPROVAL step")
  void multiStepProcedure_suspendsAtEachApproval() throws Exception {
    // Given - procedure with 3 steps
    var procedure = createMultiStepProcedure("proc-multi", 1, 3);
    var executionState = ProcedureExecutionState.initial("proc-multi", 1, Map.of("input", "value"));

    // Mock: all steps require approval
    when(bindingResolver.resolve(any(), any())).thenReturn(Map.of("param", "value"));
    when(governanceEvaluator.evaluate(any(), any()))
        .thenReturn(GovernanceDecision.REQUIRE_APPROVAL);

    // When - execute step 0
    var result0 = handler.executeNextStep(procedure, executionState);

    // Then - step 0 requires approval
    assertTrue(result0.requiresApproval(), "Step 0 should require approval");
    assertEquals("tool-0", result0.pendingCall().toolName(), "Should be waiting for tool-0");

    // When - simulate approval and execution of step 0
    var output0 = "{\"result\": \"result-0\"}";
    var state1 = handler.advanceAfterSuccess(executionState, procedure.steps().get(0), output0);

    // When - execute step 1
    var result1 = handler.executeNextStep(procedure, state1);

    // Then - step 1 also requires approval
    assertTrue(result1.requiresApproval(), "Step 1 should also require approval");
    assertEquals("tool-1", result1.pendingCall().toolName(), "Should be waiting for tool-1");
    assertEquals(1, state1.currentStepIndex(), "Should be at step 1");
  }

  @Test
  @DisplayName("3.4.5: Procedure with mixed governance (ALLOW and REQUIRE_APPROVAL)")
  void mixedGovernance_someAllowedSomeRequireApproval() throws Exception {
    // Given - procedure with 2 steps
    var procedure = createMultiStepProcedure("proc-mixed", 1, 2);
    var executionState = ProcedureExecutionState.initial("proc-mixed", 1, Map.of());

    // Mock: step 0 ALLOW, step 1 REQUIRE_APPROVAL
    when(bindingResolver.resolve(any(), any())).thenReturn(Map.of());
    when(governanceEvaluator.evaluate(any(), any()))
        .thenReturn(GovernanceDecision.ALLOW) // First call - step 0
        .thenReturn(GovernanceDecision.REQUIRE_APPROVAL); // Second call - step 1

    // When - execute step 0
    var result0 = handler.executeNextStep(procedure, executionState);

    // Then - step 0 allowed
    assertFalse(result0.requiresApproval(), "Step 0 should be allowed");
    assertTrue(result0.isAllowed(), "Step 0 should be allowed");

    // When - advance to step 1
    var output0 = "{\"result\": \"result-0\"}";
    var state1 = handler.advanceAfterSuccess(executionState, procedure.steps().get(0), output0);
    var result1 = handler.executeNextStep(procedure, state1);

    // Then - step 1 requires approval
    assertTrue(result1.requiresApproval(), "Step 1 should require approval");
    assertFalse(result1.isAllowed(), "Step 1 should not be allowed yet");
  }

  private ReusableProcedure createSimpleProcedure(String procedureId, int revision) {
    var scope = ProcedureScope.forAgent("test-agent");
    var fingerprint = new ToolCompatibilityFingerprint("testTool", "hash1");
    var step = new ProcedureStep(0, "testTool", fingerprint, Map.of(), List.of());

    return new ReusableProcedure(
        procedureId,
        revision,
        scope,
        "test query",
        List.of(step),
        ProcedureStatus.VALID,
        Instant.now(),
        null);
  }

  private ReusableProcedure createMultiStepProcedure(
      String procedureId, int revision, int stepCount) {
    var scope = ProcedureScope.forAgent("test-agent");
    var steps =
        java.util.stream.IntStream.range(0, stepCount)
            .mapToObj(
                i -> {
                  var fingerprint =
                      new ToolCompatibilityFingerprint("tool-" + i, "hash-" + i);
                  return new ProcedureStep(i, "tool-" + i, fingerprint, Map.of(), List.of());
                })
            .toList();

    return new ReusableProcedure(
        procedureId,
        revision,
        scope,
        "test query",
        steps,
        ProcedureStatus.VALID,
        Instant.now(),
        null);
  }

  private ProcedureStep createSimpleStep(int stepIndex) {
    var fingerprint = new ToolCompatibilityFingerprint("testTool", "hash1");
    return new ProcedureStep(stepIndex, "testTool", fingerprint, Map.of(), List.of());
  }
}
