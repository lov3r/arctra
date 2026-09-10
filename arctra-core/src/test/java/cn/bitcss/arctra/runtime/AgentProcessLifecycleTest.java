package cn.bitcss.arctra.runtime;

import static org.assertj.core.api.Assertions.*;

import cn.bitcss.arctra.agent.Agent;
import cn.bitcss.arctra.agent.AgentDefinition;
import cn.bitcss.arctra.agent.AgentExecutionContext;
import cn.bitcss.arctra.agent.AgentRequest;
import cn.bitcss.arctra.agent.AgentResult;
import cn.bitcss.arctra.process.AgentProcess;
import cn.bitcss.arctra.process.ContinuationSignal.ApprovalSignal;
import cn.bitcss.arctra.process.ProcessStatus;
import cn.bitcss.arctra.runtime.FakeSuspendingEngine.SuspensionMode;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * AgentProcess lifecycle foundation verification tests.
 *
 * <p>M4-T2: Verifies AgentProcess lifecycle contract through test-only infrastructure
 * (FakeSuspendingEngine). Does NOT test production suspension integration.
 *
 * <p><strong>What these tests prove:</strong>
 *
 * <ul>
 *   <li>AgentProcess contract is implementable
 *   <li>Dynamic Materialization semantic is sound
 *   <li>Process lifecycle (WAITING → resume → COMPLETED/FAILED) works
 *   <li>Concurrency/failure invariants hold
 *   <li>Session ≠ Process identity boundary maintained
 * </ul>
 *
 * <p><strong>What these tests do NOT prove:</strong>
 *
 * <ul>
 *   <li>Production Spring AI suspension integration (deferred to M4-T3)
 *   <li>Governance interception (deferred to M4-T3)
 *   <li>Real Agent reasoning continuation (deferred to M4-T3/T4)
 * </ul>
 *
 * @author lov3r
 * @since M4-T2
 */
@DisplayName("AgentProcess Lifecycle Foundation")
class AgentProcessLifecycleTest {

  @Nested
  @DisplayName("Dynamic Materialization")
  class DynamicMaterializationTests {

    @Test
    @DisplayName("Synchronous execution does not materialize process")
    void synchronousExecutionDoesNotMaterializeProcess() {
      // COMPLETE mode - no suspension
      AgentExecutionEngine engine = new FakeSuspendingEngine(SuspensionMode.COMPLETE);
      AgentRuntime runtime = new DefaultAgentRuntime(engine);
      Agent agent = runtime.agent(new AgentDefinition("test-agent", "test agent"));

      AgentResult result =
          agent.execute(new AgentRequest("test request"), AgentExecutionContext.stateless());

      // Verify no process materialized
      assertThat(result.isCompleted()).isTrue();
      assertThat(result.isSuspended()).isFalse();
      assertThat(result.process()).isNull();
      assertThat(result.content()).contains("Completed synchronously");
    }

    @Test
    @DisplayName("Suspended execution materializes process")
    void suspendedExecutionMaterializesProcess() {
      // SUSPEND_ONCE mode - suspension occurs
      AgentExecutionEngine engine = new FakeSuspendingEngine(SuspensionMode.SUSPEND_ONCE);
      AgentRuntime runtime = new DefaultAgentRuntime(engine);
      Agent agent = runtime.agent(new AgentDefinition("test-agent", "test agent"));

      AgentResult result =
          agent.execute(new AgentRequest("test request"), AgentExecutionContext.stateless());

      // Verify process materialized
      assertThat(result.isSuspended()).isTrue();
      assertThat(result.isCompleted()).isFalse();
      assertThat(result.process()).isNotNull();
      assertThat(result.process().id()).isNotBlank();
      assertThat(result.process().status()).isEqualTo(ProcessStatus.WAITING);
    }
  }

  @Nested
  @DisplayName("Lifecycle Transitions")
  class LifecycleTransitionTests {

    @Test
    @DisplayName("Resume with approval completes process")
    void resumeWithApprovalCompletesProcess() {
      // Setup suspended process
      AgentExecutionEngine engine = new FakeSuspendingEngine(SuspensionMode.SUSPEND_ONCE);
      AgentRuntime runtime = new DefaultAgentRuntime(engine);
      Agent agent = runtime.agent(new AgentDefinition("test-agent", "test agent"));

      AgentResult suspendedResult =
          agent.execute(new AgentRequest("test request"), AgentExecutionContext.stateless());
      AgentProcess process = suspendedResult.process();
      String originalId = process.id();

      // Resume with approval
      ApprovalSignal signal = new ApprovalSignal(true, "approved by test");
      AgentResult completedResult = process.resume(signal);

      // Verify completion
      assertThat(completedResult.isCompleted()).isTrue();
      assertThat(completedResult.isSuspended()).isFalse();
      assertThat(process.status()).isEqualTo(ProcessStatus.COMPLETED);
      assertThat(process.id()).isEqualTo(originalId); // Identity stable
      assertThat(completedResult.content()).contains("Resumed and completed");
      assertThat(completedResult.content()).contains("approved by test");

      // Verify result() accessible after completion
      AgentResult retrievedResult = process.result();
      assertThat(retrievedResult).isEqualTo(completedResult);
    }

    @Test
    @DisplayName("Resume with denial completes with denial result")
    void resumeWithDenialCompletesWithDenialResult() {
      // Setup suspended process
      AgentExecutionEngine engine = new FakeSuspendingEngine(SuspensionMode.SUSPEND_ONCE);
      AgentRuntime runtime = new DefaultAgentRuntime(engine);
      Agent agent = runtime.agent(new AgentDefinition("test-agent", "test agent"));

      AgentResult suspendedResult =
          agent.execute(new AgentRequest("test request"), AgentExecutionContext.stateless());
      AgentProcess process = suspendedResult.process();

      // Resume with denial
      ApprovalSignal signal = new ApprovalSignal(false, "denied by test");
      AgentResult deniedResult = process.resume(signal);

      // Verify completed with denial
      assertThat(deniedResult.isCompleted()).isTrue();
      assertThat(process.status()).isEqualTo(ProcessStatus.COMPLETED);
      assertThat(deniedResult.content()).contains("Execution denied");
      assertThat(deniedResult.content()).contains("denied by test");
    }

    @Test
    @DisplayName("ProcessId remains stable across lifecycle")
    void processIdRemainsStableAcrossLifecycle() {
      AgentExecutionEngine engine = new FakeSuspendingEngine(SuspensionMode.SUSPEND_ONCE);
      AgentRuntime runtime = new DefaultAgentRuntime(engine);
      Agent agent = runtime.agent(new AgentDefinition("test-agent", "test agent"));

      AgentResult suspendedResult =
          agent.execute(new AgentRequest("test"), AgentExecutionContext.stateless());
      AgentProcess process = suspendedResult.process();

      String idAtSuspension = process.id();
      ProcessStatus statusAtSuspension = process.status();

      // Resume
      process.resume(new ApprovalSignal(true, "approved"));

      String idAfterResume = process.id();
      ProcessStatus statusAfterResume = process.status();

      // Verify identity stable, status changed
      assertThat(idAfterResume).isEqualTo(idAtSuspension);
      assertThat(statusAtSuspension).isEqualTo(ProcessStatus.WAITING);
      assertThat(statusAfterResume).isEqualTo(ProcessStatus.COMPLETED);
    }
  }

  @Nested
  @DisplayName("Re-Suspension")
  class ReSuspensionTests {

    @Test
    @DisplayName("Re-suspension maintains stable process identity")
    void reSuspensionMaintainsStableProcessIdentity() {
      // SUSPEND_TWICE mode - multi-phase suspension
      AgentExecutionEngine engine = new FakeSuspendingEngine(SuspensionMode.SUSPEND_TWICE);
      AgentRuntime runtime = new DefaultAgentRuntime(engine);
      Agent agent = runtime.agent(new AgentDefinition("test-agent", "test agent"));

      // Initial suspension
      AgentResult result1 =
          agent.execute(new AgentRequest("test"), AgentExecutionContext.stateless());
      assertThat(result1.isSuspended()).isTrue();

      AgentProcess process = result1.process();
      String originalProcessId = process.id();
      assertThat(process.status()).isEqualTo(ProcessStatus.WAITING);

      // Resume phase 1 - should suspend again WITH SAME PROCESS
      AgentResult result2 = process.resume(new ApprovalSignal(true, "phase 1 approved"));

      assertThat(result2.isSuspended()).isTrue();
      assertThat(result2.content()).contains("Phase 1 approved");

      // Critical: Same process instance, same processId
      assertThat(result2.process()).isSameAs(process);
      assertThat(result2.process().id()).isEqualTo(originalProcessId);
      assertThat(process.status()).isEqualTo(ProcessStatus.WAITING);

      // Resume phase 2 - complete
      AgentResult result3 = process.resume(new ApprovalSignal(true, "phase 2 approved"));

      assertThat(result3.isCompleted()).isTrue();
      assertThat(result3.content()).contains("Completed after re-suspension");
      assertThat(process.status()).isEqualTo(ProcessStatus.COMPLETED);

      // Process identity remained stable throughout
      assertThat(process.id()).isEqualTo(originalProcessId);
    }
  }

  @Nested
  @DisplayName("Boundary Verification")
  class BoundaryVerificationTests {

    @Test
    @DisplayName("SessionId != ProcessId")
    void sessionIdNotEqualToProcessId() {
      AgentExecutionEngine engine = new FakeSuspendingEngine(SuspensionMode.SUSPEND_ONCE);
      AgentRuntime runtime = new DefaultAgentRuntime(engine);
      Agent agent = runtime.agent(new AgentDefinition("test-agent", "test agent"));

      AgentExecutionContext context = AgentExecutionContext.withSession("session-123");
      AgentResult result = agent.execute(new AgentRequest("test"), context);

      AgentProcess process = result.process();

      // Verify session identity != process identity
      assertThat(context.sessionId()).isEqualTo("session-123");
      assertThat(process.id()).isNotEqualTo("session-123");
      assertThat(process.id()).isNotBlank();
    }

    @Test
    @DisplayName("Evidence survives resume without duplication")
    void evidenceSurvivesResumeWithoutDuplication() {
      AgentExecutionEngine engine = new FakeSuspendingEngine(SuspensionMode.SUSPEND_ONCE);
      AgentRuntime runtime = new DefaultAgentRuntime(engine);
      Agent agent = runtime.agent(new AgentDefinition("test-agent", "test agent"));

      AgentResult suspendedResult =
          agent.execute(new AgentRequest("test"), AgentExecutionContext.stateless());
      int suspendedEvidenceCount = suspendedResult.evidences().size();

      AgentProcess process = suspendedResult.process();
      AgentResult completedResult = process.resume(new ApprovalSignal(true, "approved"));
      int completedEvidenceCount = completedResult.evidences().size();

      // Verify evidence accumulated, not duplicated
      assertThat(completedEvidenceCount).isGreaterThan(suspendedEvidenceCount);

      // Verify initial evidence still present
      assertThat(completedResult.evidences())
          .anyMatch(e -> e.source().equals("fake:suspension"));

      // Verify new evidence added
      assertThat(completedResult.evidences()).anyMatch(e -> e.source().equals("fake:approval"));
    }
  }

  @Nested
  @DisplayName("Concurrency Protection")
  class ConcurrencyProtectionTests {

    @Test
    @DisplayName("Concurrent resume executes continuation at most once")
    void concurrentResumeExecutesContinuationAtMostOnce() throws Exception {
      AgentExecutionEngine engine = new FakeSuspendingEngine(SuspensionMode.SUSPEND_ONCE);
      AgentRuntime runtime = new DefaultAgentRuntime(engine);
      Agent agent = runtime.agent(new AgentDefinition("test-agent", "test agent"));

      AgentResult suspendedResult =
          agent.execute(new AgentRequest("test"), AgentExecutionContext.stateless());
      AgentProcess process = suspendedResult.process();

      // Attempt concurrent resume
      ApprovalSignal signal1 = new ApprovalSignal(true, "thread 1");
      ApprovalSignal signal2 = new ApprovalSignal(true, "thread 2");

      CompletableFuture<AgentResult> future1 =
          CompletableFuture.supplyAsync(() -> process.resume(signal1));

      CompletableFuture<AgentResult> future2 =
          CompletableFuture.supplyAsync(() -> process.resume(signal2));

      // One should succeed, one should fail
      boolean future1Success = false;
      boolean future2Success = false;

      try {
        future1.get();
        future1Success = true;
      } catch (ExecutionException e) {
        assertThat(e.getCause()).isInstanceOf(IllegalStateException.class);
      }

      try {
        future2.get();
        future2Success = true;
      } catch (ExecutionException e) {
        assertThat(e.getCause()).isInstanceOf(IllegalStateException.class);
      }

      // Exactly one should succeed
      assertThat(future1Success ^ future2Success)
          .as("Exactly one resume should succeed")
          .isTrue();

      // Process should be completed
      assertThat(process.status()).isEqualTo(ProcessStatus.COMPLETED);
    }
  }

  @Nested
  @DisplayName("Failure Semantics")
  class FailureSemanticsTests {

    @Test
    @DisplayName("Resume after COMPLETED throws IllegalStateException")
    void resumeAfterCompletedThrowsIllegalStateException() {
      AgentExecutionEngine engine = new FakeSuspendingEngine(SuspensionMode.SUSPEND_ONCE);
      AgentRuntime runtime = new DefaultAgentRuntime(engine);
      Agent agent = runtime.agent(new AgentDefinition("test-agent", "test agent"));

      AgentResult suspendedResult =
          agent.execute(new AgentRequest("test"), AgentExecutionContext.stateless());
      AgentProcess process = suspendedResult.process();

      // Complete process
      process.resume(new ApprovalSignal(true, "approved"));
      assertThat(process.status()).isEqualTo(ProcessStatus.COMPLETED);

      // Attempt second resume
      assertThatThrownBy(() -> process.resume(new ApprovalSignal(true, "second approval")))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("COMPLETED");
    }

    @Test
    @DisplayName("result() before COMPLETED throws IllegalStateException")
    void resultBeforeCompletedThrowsIllegalStateException() {
      AgentExecutionEngine engine = new FakeSuspendingEngine(SuspensionMode.SUSPEND_ONCE);
      AgentRuntime runtime = new DefaultAgentRuntime(engine);
      Agent agent = runtime.agent(new AgentDefinition("test-agent", "test agent"));

      AgentResult suspendedResult =
          agent.execute(new AgentRequest("test"), AgentExecutionContext.stateless());
      AgentProcess process = suspendedResult.process();

      assertThat(process.status()).isEqualTo(ProcessStatus.WAITING);

      // Attempt result() before completion
      assertThatThrownBy(() -> process.result())
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("COMPLETED");
    }

    @Test
    @DisplayName("Continuation failure transitions process to FAILED")
    void continuationFailureTransitionsProcessToFailed() {
      // Create process with failing continuation
      java.util.function.Function<cn.bitcss.arctra.process.ContinuationSignal, AgentResult>
          failingContinuation =
              signal -> {
                throw new RuntimeException("Simulated continuation failure");
              };

      AgentProcess process = new DefaultAgentProcess(failingContinuation);

      assertThat(process.status()).isEqualTo(ProcessStatus.WAITING);

      // M4-T4: Resume fails and transitions to FAILED
      // Original exception is rethrown unchanged (not wrapped)
      assertThatThrownBy(() -> process.resume(new ApprovalSignal(true, "approved")))
          .isInstanceOf(RuntimeException.class)
          .hasMessage("Simulated continuation failure")  // Original message
          .hasNoCause();  // Not wrapped

      assertThat(process.status()).isEqualTo(ProcessStatus.FAILED);
    }
  }

  @Nested
  @DisplayName("Backward Compatibility")
  class BackwardCompatibilityTests {

    @Test
    @DisplayName("M1-M3 synchronous execution pattern unchanged")
    void m1M3SynchronousExecutionPatternUnchanged() {
      // M1-M3 pattern: synchronous execution with no Process concept
      AgentExecutionEngine engine = new FakeSuspendingEngine(SuspensionMode.COMPLETE);
      AgentRuntime runtime = new DefaultAgentRuntime(engine);
      Agent agent = runtime.agent(new AgentDefinition("test-agent", "test agent"));

      // Old pattern still works
      AgentResult result = agent.execute(new AgentRequest("test"));

      assertThat(result.content()).isNotBlank();
      assertThat(result.evidences()).isNotNull();

      // New helper methods work
      assertThat(result.isCompleted()).isTrue();
      assertThat(result.isSuspended()).isFalse();

      // Process field exists but null for completed execution
      assertThat(result.process()).isNull();
    }
  }
}
