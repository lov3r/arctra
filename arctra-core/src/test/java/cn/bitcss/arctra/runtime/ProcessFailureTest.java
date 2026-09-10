package cn.bitcss.arctra.runtime;

import static org.assertj.core.api.Assertions.*;

import cn.bitcss.arctra.agent.Agent;
import cn.bitcss.arctra.agent.AgentDefinition;
import cn.bitcss.arctra.agent.AgentExecutionContext;
import cn.bitcss.arctra.agent.AgentRequest;
import cn.bitcss.arctra.agent.AgentResult;
import cn.bitcss.arctra.process.AgentProcess;
import cn.bitcss.arctra.process.ContinuationSignal;
import cn.bitcss.arctra.process.ContinuationSignal.ApprovalSignal;
import cn.bitcss.arctra.process.ProcessStatus;
import cn.bitcss.arctra.runtime.FakeSuspendingEngine.SuspensionMode;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * M4-T4: Process failure semantics tests.
 *
 * <p>Verifies that AgentProcess correctly handles continuation failures and transitions to FAILED
 * state while preserving stable processId.
 */
@DisplayName("M4-T4: Process Failure Semantics")
class ProcessFailureTest {

  /**
   * Test A: Resume failure transitions current Process to FAILED.
   *
   * <p>WAITING → RUNNING → FAILED (exception propagates, processId stable)
   */
  @Test
  @DisplayName("Resume failure transitions to FAILED with stable processId")
  void resumeFailure_transitionsToFailed_stableProcessId() {
    // Arrange: Create a process that will fail on resume
    Function<ContinuationSignal, AgentResult> failingContinuation =
        signal -> {
          throw new RuntimeException("Continuation failed");
        };

    AgentProcess process = new DefaultAgentProcess(failingContinuation);
    String processId = process.id();

    assertThat(process.status()).isEqualTo(ProcessStatus.WAITING);

    // Act: Resume → failure
    RuntimeException thrown =
        catchThrowableOfType(
            () -> process.resume(new ApprovalSignal(true, "approved")), RuntimeException.class);

    // Assert: Exception propagated
    assertThat(thrown).isNotNull().hasMessage("Continuation failed");

    // Assert: Process transitioned to FAILED
    assertThat(process.status()).isEqualTo(ProcessStatus.FAILED);

    // Assert: processId unchanged (stable identity)
    assertThat(process.id()).isEqualTo(processId);
  }

  /** Test B: FAILED process cannot resume. */
  @Test
  @DisplayName("FAILED process cannot resume")
  void failedProcess_cannotResume() {
    // Arrange: Create a process that fails on resume
    Function<ContinuationSignal, AgentResult> failingContinuation =
        signal -> {
          throw new RuntimeException("Continuation failed");
        };

    AgentProcess process = new DefaultAgentProcess(failingContinuation);

    // Fail the process
    catchThrowable(() -> process.resume(new ApprovalSignal(true, "approved")));
    assertThat(process.status()).isEqualTo(ProcessStatus.FAILED);

    // Act & Assert: Cannot resume again
    assertThatThrownBy(() -> process.resume(new ApprovalSignal(true, "approved")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Cannot resume process in state FAILED");
  }

  /** Test C: FAILED process result() unavailable. */
  @Test
  @DisplayName("FAILED process result() throws IllegalStateException")
  void failedProcess_resultUnavailable() {
    // Arrange: Create a process that fails on resume
    Function<ContinuationSignal, AgentResult> failingContinuation =
        signal -> {
          throw new RuntimeException("Continuation failed");
        };

    AgentProcess process = new DefaultAgentProcess(failingContinuation);

    // Fail the process
    catchThrowable(() -> process.resume(new ApprovalSignal(true, "approved")));
    assertThat(process.status()).isEqualTo(ProcessStatus.FAILED);

    // Act & Assert: result() throws
    assertThatThrownBy(() -> process.result())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Result only available when COMPLETED");
  }

  /** Test D: WAITING process result() unavailable (regression). */
  @Test
  @DisplayName("WAITING process result() throws IllegalStateException")
  void waitingProcess_resultUnavailable() {
    // Arrange: Create a waiting process
    Function<ContinuationSignal, AgentResult> continuation =
        signal -> new AgentResult("completed", List.of());

    AgentProcess process = new DefaultAgentProcess(continuation);

    assertThat(process.status()).isEqualTo(ProcessStatus.WAITING);

    // Act & Assert: result() throws
    assertThatThrownBy(() -> process.result())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Result only available when COMPLETED");
  }

  /** Test E: Successful completion regression. */
  @Test
  @DisplayName("Successful completion after suspension - existing semantics unchanged")
  void successfulCompletion_afterSuspension_unchanged() {
    // Arrange
    AgentExecutionEngine engine = new FakeSuspendingEngine(SuspensionMode.SUSPEND_ONCE);
    AgentRuntime runtime = new DefaultAgentRuntime(engine);
    Agent agent = runtime.agent(new AgentDefinition("test-agent", "test"));

    // Act: Initial suspension
    AgentResult result1 = agent.execute(new AgentRequest("test"));
    AgentProcess process = result1.process();
    String processId = process.id();

    assertThat(process.status()).isEqualTo(ProcessStatus.WAITING);

    // Act: Resume to completion
    AgentResult result2 = process.resume(new ApprovalSignal(true, "approved"));

    // Assert: Completed
    assertThat(result2.isCompleted()).isTrue();

    // Assert: If process exists in result, verify identity and status
    if (result2.process() != null) {
      assertThat(result2.process().id()).isEqualTo(processId);
      assertThat(result2.process().status()).isEqualTo(ProcessStatus.COMPLETED);
    }
  }

  /** Test F: Re-suspension regression - stable processId, different object. */
  @Test
  @DisplayName("Re-suspension maintains stable processId (not object identity)")
  void reSuspension_stableProcessId_notObjectIdentity() {
    // Arrange: Engine that suspends twice
    AgentExecutionEngine engine = new FakeSuspendingEngine(SuspensionMode.SUSPEND_TWICE);
    AgentRuntime runtime = new DefaultAgentRuntime(engine);
    Agent agent = runtime.agent(new AgentDefinition("test-agent", "test"));

    // Act: First suspension
    AgentResult result1 = agent.execute(new AgentRequest("test"));
    AgentProcess process1 = result1.process();
    String processId1 = process1.id();

    assertThat(process1.status()).isEqualTo(ProcessStatus.WAITING);

    // Act: First resume → re-suspension
    AgentResult result2 = process1.resume(new ApprovalSignal(true, "approved"));
    AgentProcess process2 = result2.process();

    // Assert: Re-suspended
    assertThat(result2.isSuspended()).isTrue();
    assertThat(process2.status()).isEqualTo(ProcessStatus.WAITING);

    // Assert: processId stable (NOT object identity)
    assertThat(process2.id()).isEqualTo(processId1);

    // Note: We assert process1 == process2 because current implementation
    // returns same object with updated continuation (stable object identity)
    assertThat(process2).isSameAs(process1);
  }

  /** Test G: Initial execution failure - no Process materialized. */
  @Test
  @DisplayName("Initial execution failure - exception propagates, no Process materialized")
  void initialExecutionFailure_noProcessMaterialized() {
    // Arrange: Engine that fails immediately
    AgentExecutionEngine engine =
        new FakeSuspendingEngine(SuspensionMode.COMPLETE) {
          @Override
          public AgentResult execute(
              AgentDefinition definition, AgentRequest request, AgentExecutionContext context) {
            throw new RuntimeException("Initial execution failed");
          }
        };

    AgentRuntime runtime = new DefaultAgentRuntime(engine);
    Agent agent = runtime.agent(new AgentDefinition("test-agent", "test"));

    // Act & Assert: Exception propagates
    assertThatThrownBy(() -> agent.execute(new AgentRequest("test")))
        .isInstanceOf(RuntimeException.class)
        .hasMessage("Initial execution failed");

    // No Process to check - Dynamic Materialization preserved
  }

  /**
   * Test H: Suspended Process resume runtime failure.
   *
   * <p>Realistic flow with actual Process lifecycle.
   */
  @Test
  @DisplayName("Suspended Process resume fails - transitions to FAILED, exception propagates")
  void suspendedProcess_resumeFails_transitionsToFailed() {
    // Arrange: Use FakeSuspendingEngine to create a real suspended process
    AgentExecutionEngine engine = new FakeSuspendingEngine(SuspensionMode.SUSPEND_ONCE);
    AgentRuntime runtime = new DefaultAgentRuntime(engine);
    Agent agent = runtime.agent(new AgentDefinition("test-agent", "test"));

    // Act: Execute → suspension
    AgentResult result1 = agent.execute(new AgentRequest("test"));
    assertThat(result1.isSuspended()).isTrue();

    AgentProcess process = result1.process();
    String processId = process.id();

    // Now manually create a failing continuation to replace the working one
    // This simulates resume failure in production code
    if (process instanceof DefaultAgentProcess defaultProcess) {
      // Access package-private field via reflection for testing
      try {
        var field = DefaultAgentProcess.class.getDeclaredField("strategy");
        field.setAccessible(true);
        // Create a failing ephemeral strategy
        Function<ContinuationSignal, AgentResult> failingContinuation =
            signal -> {
              throw new RuntimeException("Resume continuation failed");
            };
        ResumeStrategy failingStrategy = new EphemeralResumeStrategy(failingContinuation);
        field.set(defaultProcess, failingStrategy);
      } catch (Exception e) {
        throw new RuntimeException("Test setup failed", e);
      }
    }

    // Act: Resume → failure
    RuntimeException thrown =
        catchThrowableOfType(
            () -> process.resume(new ApprovalSignal(true, "approved")), RuntimeException.class);

    // Assert
    assertThat(thrown).hasMessage("Resume continuation failed");
    assertThat(process.status()).isEqualTo(ProcessStatus.FAILED);
    assertThat(process.id()).isEqualTo(processId);
  }

  /** Test I: REQUIRE_APPROVAL regression - produces WAITING, not FAILED. */
  @Test
  @DisplayName("REQUIRE_APPROVAL produces WAITING, not FAILED")
  void requireApproval_producesWaiting_notFailed() {
    // Arrange
    AgentExecutionEngine engine = new FakeSuspendingEngine(SuspensionMode.SUSPEND_ONCE);
    AgentRuntime runtime = new DefaultAgentRuntime(engine);
    Agent agent = runtime.agent(new AgentDefinition("test-agent", "test"));

    // Act: Execute → suspension (simulates REQUIRE_APPROVAL)
    AgentResult result = agent.execute(new AgentRequest("test"));

    // Assert: WAITING, not FAILED
    assertThat(result.isSuspended()).isTrue();
    AgentProcess process = result.process();
    assertThat(process.status()).isEqualTo(ProcessStatus.WAITING);
    assertThat(process.status()).isNotEqualTo(ProcessStatus.FAILED);
  }

  /** Test J: Re-suspension runtime regression - stable processId through two suspensions. */
  @Test
  @DisplayName("Re-suspension runtime - stable processId, correct status, completion works")
  void reSuspension_runtime_stableProcessId_completion() {
    // Arrange
    AgentExecutionEngine engine = new FakeSuspendingEngine(SuspensionMode.SUSPEND_TWICE);
    AgentRuntime runtime = new DefaultAgentRuntime(engine);
    Agent agent = runtime.agent(new AgentDefinition("test-agent", "test"));

    // Act: First suspension
    AgentResult result1 = agent.execute(new AgentRequest("test"));
    AgentProcess process1 = result1.process();
    String processId1 = process1.id();

    assertThat(process1.status()).isEqualTo(ProcessStatus.WAITING);

    // Act: First approval → re-suspension
    AgentResult result2 = process1.resume(new ApprovalSignal(true, "approved"));
    assertThat(result2.isSuspended()).isTrue();

    AgentProcess process2 = result2.process();
    assertThat(process2.status()).isEqualTo(ProcessStatus.WAITING);
    assertThat(process2.id()).isEqualTo(processId1);  // Stable processId

    // Act: Second approval → completion
    AgentResult result3 = process2.resume(new ApprovalSignal(true, "approved"));
    assertThat(result3.isCompleted()).isTrue();

    // Assert: Final verification
    if (result3.process() != null) {
      assertThat(result3.process().id()).isEqualTo(processId1);  // Still same processId
      assertThat(result3.process().status()).isEqualTo(ProcessStatus.COMPLETED);
    }
  }

  /**
   * Additional Test: Multiple resume attempts on FAILED process.
   *
   * <p>Verifies FAILED is truly terminal.
   */
  @Test
  @DisplayName("FAILED is terminal - multiple resume attempts rejected")
  void failedIsTerminal_multipleResumeRejected() {
    // Arrange: Failed process
    Function<ContinuationSignal, AgentResult> failingContinuation =
        signal -> {
          throw new RuntimeException("Failure");
        };

    AgentProcess process = new DefaultAgentProcess(failingContinuation);

    // Fail it
    catchThrowable(() -> process.resume(new ApprovalSignal(true, "approved")));
    assertThat(process.status()).isEqualTo(ProcessStatus.FAILED);

    // Act & Assert: Multiple resume attempts all rejected
    for (int i = 0; i < 3; i++) {
      assertThatThrownBy(() -> process.resume(new ApprovalSignal(true, "approved")))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("Cannot resume process in state FAILED");

      // Status remains FAILED
      assertThat(process.status()).isEqualTo(ProcessStatus.FAILED);
    }
  }

  /**
   * Additional Test: Different exception types are preserved.
   *
   * <p>Verifies original exception is rethrown unchanged.
   */
  @Test
  @DisplayName("Original exception type preserved during failure")
  void originalExceptionTypePreserved() {
    // Arrange: Process that throws specific exception
    Function<ContinuationSignal, AgentResult> specificFailureContinuation =
        signal -> {
          throw new IllegalArgumentException("Specific failure reason");
        };

    AgentProcess process = new DefaultAgentProcess(specificFailureContinuation);

    // Act: Resume → failure
    Throwable thrown = catchThrowable(() -> process.resume(new ApprovalSignal(true, "approved")));

    // Assert: Original exception type and message preserved
    assertThat(thrown)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Specific failure reason")
        .hasNoCause();  // Not wrapped

    assertThat(process.status()).isEqualTo(ProcessStatus.FAILED);
  }
}
