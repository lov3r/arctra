package cn.bitcss.arctra.runtime;

import static org.assertj.core.api.Assertions.*;

import cn.bitcss.arctra.agent.AgentDefinition;
import cn.bitcss.arctra.agent.AgentExecutionContext;
import cn.bitcss.arctra.agent.AgentRequest;
import cn.bitcss.arctra.agent.AgentResult;
import cn.bitcss.arctra.checkpoint.CheckpointNotFoundException;
import cn.bitcss.arctra.checkpoint.CheckpointTransitionConflictException;
import cn.bitcss.arctra.checkpoint.StaleCheckpointException;
import cn.bitcss.arctra.process.AgentProcess;
import cn.bitcss.arctra.process.ContinuationSignal;
import cn.bitcss.arctra.process.ProcessStatus;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * M5-T4 Phase 6: Local AgentProcess exception lifecycle tests.
 *
 * <p>Verifies correct local handle status transitions when durable resume throws various exception
 * types, based on strategy validity rather than exception class name alone.
 *
 * @author lov3r
 * @since M5-T4 Phase 6
 */
@DisplayName("M5-T4 Phase 6: Local Handle Exception Lifecycle")
class ProcessExceptionLifecycleTest {

  /**
   * Fake DurableExecutionEngine for testing.
   */
  private abstract static class FakeDurableEngine implements DurableExecutionEngine {
    @Override
    public AgentResult execute(
        AgentDefinition definition, AgentRequest request, AgentExecutionContext context) {
      throw new UnsupportedOperationException("Not used in tests");
    }
  }

  /**
   * Invariant #1: ResumePreparationException → WAITING (retryable).
   *
   * <p>Preparation failure before tool/model execution. Checkpoint unchanged, same strategy/version
   * remains valid.
   */
  @Test
  @DisplayName("ResumePreparationException → WAITING, retryable")
  void resumePreparationException_waiting_retryable() {
    AtomicInteger attemptCount = new AtomicInteger(0);

    // Fake durable engine: first call throws ResumePreparationException, second succeeds
    DurableExecutionEngine engine =
        new FakeDurableEngine() {
          @Override
          public AgentResult resumeProcess(
              String processId, long checkpointVersion, ContinuationSignal signal) {
            int attempt = attemptCount.incrementAndGet();
            if (attempt == 1) {
              throw new ResumePreparationException("Binding resolution failed", null);
            }
            return new AgentResult("completed", List.of());
          }
        };

    // Create durable process
    DurableResumeStrategy strategy = new DurableResumeStrategy("process-1", 1L, engine);
    AgentProcess process = DefaultAgentProcess.withStrategy("process-1", strategy);

    assertThat(process.status()).isEqualTo(ProcessStatus.WAITING);

    // First resume → ResumePreparationException → WAITING
    assertThatThrownBy(() -> process.resume(new ContinuationSignal.ApprovalSignal(true, "approved")))
        .isInstanceOf(ResumePreparationException.class);

    assertThat(process.status())
        .as("After ResumePreparationException, handle should remain WAITING (retryable)")
        .isEqualTo(ProcessStatus.WAITING);

    // Second resume → succeeds
    AgentResult result = process.resume(new ContinuationSignal.ApprovalSignal(true, "approved"));

    assertThat(result.isCompleted()).isTrue();
    assertThat(process.status()).isEqualTo(ProcessStatus.COMPLETED);
    assertThat(attemptCount.get()).isEqualTo(2);
  }

  /**
   * Invariant #2: StaleCheckpointException → FAILED (stale handle).
   *
   * <p>Local handle has old version, checkpoint advanced externally. Handle no longer usable.
   */
  @Test
  @DisplayName("StaleCheckpointException → FAILED, second call blocked at CAS")
  void staleCheckpointException_failed_secondCallBlocked() {
    AtomicInteger engineCallCount = new AtomicInteger(0);

    DurableExecutionEngine engine =
        new FakeDurableEngine() {
          @Override
          public AgentResult resumeProcess(
              String processId, long checkpointVersion, ContinuationSignal signal) {
            engineCallCount.incrementAndGet();
            throw new StaleCheckpointException(
                "Checkpoint version mismatch for processId " + processId);
          }
        };

    DurableResumeStrategy strategy = new DurableResumeStrategy("process-1", 1L, engine);
    AgentProcess process = DefaultAgentProcess.withStrategy("process-1", strategy);

    // First resume → StaleCheckpointException → FAILED
    assertThatThrownBy(() -> process.resume(new ContinuationSignal.ApprovalSignal(true, "approved")))
        .isInstanceOf(StaleCheckpointException.class);

    assertThat(process.status())
        .as("Stale handle should be FAILED (unusable)")
        .isEqualTo(ProcessStatus.FAILED);

    assertThat(engineCallCount.get()).isEqualTo(1);

    // Second resume → blocked at CAS (state check), engine NOT called
    assertThatThrownBy(() -> process.resume(new ContinuationSignal.ApprovalSignal(true, "approved")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Cannot resume process in state FAILED");

    assertThat(engineCallCount.get())
        .as("Engine should NOT be called again after FAILED")
        .isEqualTo(1);
  }

  /**
   * Invariant #3: CheckpointNotFoundException → FAILED (no backing checkpoint).
   *
   * <p>Checkpoint missing (completed elsewhere, deleted, or storage inconsistency). Local handle
   * unusable.
   */
  @Test
  @DisplayName("CheckpointNotFoundException → FAILED")
  void checkpointNotFoundException_failed() {
    DurableExecutionEngine engine =
        new FakeDurableEngine() {
          @Override
          public AgentResult resumeProcess(
              String processId, long checkpointVersion, ContinuationSignal signal) {
            throw new CheckpointNotFoundException("Checkpoint not found for processId: " + processId);
          }
        };

    DurableResumeStrategy strategy = new DurableResumeStrategy("process-1", 1L, engine);
    AgentProcess process = DefaultAgentProcess.withStrategy("process-1", strategy);

    // Resume → CheckpointNotFoundException → FAILED
    assertThatThrownBy(() -> process.resume(new ContinuationSignal.ApprovalSignal(true, "approved")))
        .isInstanceOf(CheckpointNotFoundException.class);

    assertThat(process.status())
        .as("Handle without backing checkpoint should be FAILED (unusable)")
        .isEqualTo(ProcessStatus.FAILED);
  }

  /**
   * Invariant #4: CheckpointTransitionConflictException → FAILED (lost CHECK B race).
   *
   * <p>Execution performed work but lost CHECK B race. Handle stale/unusable.
   */
  @Test
  @DisplayName("CheckpointTransitionConflictException → FAILED")
  void checkpointTransitionConflictException_failed() {
    DurableExecutionEngine engine =
        new FakeDurableEngine() {
          @Override
          public AgentResult resumeProcess(
              String processId, long checkpointVersion, ContinuationSignal signal) {
            throw new CheckpointTransitionConflictException(
                "CHECK B conflict for processId " + processId);
          }
        };

    DurableResumeStrategy strategy = new DurableResumeStrategy("process-1", 1L, engine);
    AgentProcess process = DefaultAgentProcess.withStrategy("process-1", strategy);

    // Resume → CheckpointTransitionConflictException → FAILED
    assertThatThrownBy(() -> process.resume(new ContinuationSignal.ApprovalSignal(true, "approved")))
        .isInstanceOf(CheckpointTransitionConflictException.class);

    assertThat(process.status())
        .as("Handle after CHECK B conflict should be FAILED (stale)")
        .isEqualTo(ProcessStatus.FAILED);
  }

  /**
   * Invariant #5: Tool/model/backend failure → FAILED (M4 semantics preserved).
   */
  @Test
  @DisplayName("Tool/model failure → FAILED")
  void toolModelFailure_failed() {
    DurableExecutionEngine engine =
        new FakeDurableEngine() {
          @Override
          public AgentResult resumeProcess(
              String processId, long checkpointVersion, ContinuationSignal signal) {
            throw new RuntimeException("Tool execution failed");
          }
        };

    DurableResumeStrategy strategy = new DurableResumeStrategy("process-1", 1L, engine);
    AgentProcess process = DefaultAgentProcess.withStrategy("process-1", strategy);

    // Resume → RuntimeException → FAILED
    assertThatThrownBy(() -> process.resume(new ContinuationSignal.ApprovalSignal(true, "approved")))
        .isInstanceOf(RuntimeException.class)
        .hasMessage("Tool execution failed");

    assertThat(process.status()).isEqualTo(ProcessStatus.FAILED);
  }

  /**
   * Invariant #6: M4 ephemeral failure → FAILED (regression).
   */
  @Test
  @DisplayName("M4 ephemeral failure → FAILED (unchanged)")
  void ephemeralFailure_failed_unchanged() {
    // M4 ephemeral continuation that fails
    java.util.function.Function<ContinuationSignal, AgentResult> failingContinuation =
        signal -> {
          throw new RuntimeException("Ephemeral continuation failed");
        };

    AgentProcess process = new DefaultAgentProcess(failingContinuation);

    // Resume → failure → FAILED
    assertThatThrownBy(() -> process.resume(new ContinuationSignal.ApprovalSignal(true, "approved")))
        .isInstanceOf(RuntimeException.class);

    assertThat(process.status()).isEqualTo(ProcessStatus.FAILED);
  }

  /**
   * Invariant #7: Same-handle concurrency unchanged (regression).
   */
  @Test
  @DisplayName("Same-handle concurrency - only one CAS succeeds")
  void sameHandleConcurrency_oneCasSucceeds() throws Exception {
    AtomicInteger executionCount = new AtomicInteger(0);

    java.util.function.Function<ContinuationSignal, AgentResult> continuation =
        signal -> {
          executionCount.incrementAndGet();
          try {
            Thread.sleep(100); // Simulate work
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          return new AgentResult("completed", List.of());
        };

    AgentProcess process = new DefaultAgentProcess(continuation);

    // Launch 3 concurrent resume attempts
    java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(3);
    java.util.concurrent.atomic.AtomicInteger successCount = new java.util.concurrent.atomic.AtomicInteger(0);
    java.util.concurrent.atomic.AtomicInteger rejectedCount = new java.util.concurrent.atomic.AtomicInteger(0);

    for (int i = 0; i < 3; i++) {
      new Thread(
              () -> {
                try {
                  process.resume(new ContinuationSignal.ApprovalSignal(true, "approved"));
                  successCount.incrementAndGet();
                } catch (IllegalStateException e) {
                  if (e.getMessage().contains("Cannot resume process in state")) {
                    rejectedCount.incrementAndGet();
                  }
                } finally {
                  latch.countDown();
                }
              })
          .start();
    }

    latch.await();

    // Only 1 execution succeeds, 2 rejected at CAS
    assertThat(successCount.get()).isEqualTo(1);
    assertThat(rejectedCount.get()).isEqualTo(2);
    assertThat(executionCount.get()).isEqualTo(1);
  }

  /**
   * Invariant #8: Successful completion → COMPLETED (regression).
   */
  @Test
  @DisplayName("Successful completion → COMPLETED")
  void successfulCompletion_completed() {
    java.util.function.Function<ContinuationSignal, AgentResult> continuation =
        signal -> new AgentResult("completed", List.of());

    AgentProcess process = new DefaultAgentProcess(continuation);

    AgentResult result = process.resume(new ContinuationSignal.ApprovalSignal(true, "approved"));

    assertThat(result.isCompleted()).isTrue();
    assertThat(process.status()).isEqualTo(ProcessStatus.COMPLETED);
  }

  /**
   * Invariant #9: Successful re-suspension → WAITING with adopted strategy (regression).
   */
  @Test
  @DisplayName("Successful re-suspension → WAITING with adopted strategy")
  void successfulReSuspension_waitingWithAdoptedStrategy() {
    AtomicInteger callCount = new AtomicInteger(0);

    // First call returns suspended process v2, second call completes
    DurableExecutionEngine engine =
        new FakeDurableEngine() {
          @Override
          public AgentResult resumeProcess(
              String processId, long checkpointVersion, ContinuationSignal signal) {
            int call = callCount.incrementAndGet();
            if (call == 1) {
              // Re-suspension: return new process with v2 strategy
              DurableResumeStrategy newStrategy =
                  new DurableResumeStrategy(processId, 2L, this);
              AgentProcess newProcess = DefaultAgentProcess.withStrategy(processId, newStrategy);
              return new AgentResult("partial", List.of(), newProcess);
            } else {
              // Completion
              return new AgentResult("completed", List.of());
            }
          }
        };

    DurableResumeStrategy strategy = new DurableResumeStrategy("process-1", 1L, engine);
    AgentProcess process = DefaultAgentProcess.withStrategy("process-1", strategy);

    // First resume → re-suspension
    AgentResult result1 = process.resume(new ContinuationSignal.ApprovalSignal(true, "approved"));

    assertThat(result1.isSuspended()).isTrue();
    assertThat(process.status()).isEqualTo(ProcessStatus.WAITING);
    assertThat(result1.process()).isSameAs(process); // Stable identity

    // Second resume uses adopted strategy
    AgentResult result2 = process.resume(new ContinuationSignal.ApprovalSignal(true, "approved"));

    assertThat(result2.isCompleted()).isTrue();
    assertThat(process.status()).isEqualTo(ProcessStatus.COMPLETED);
    assertThat(callCount.get()).isEqualTo(2);
  }

  /**
   * Invariant #10: Second resume after vN→vN+1 uses new strategy/version (regression).
   */
  @Test
  @DisplayName("Second resume after re-suspension uses new strategy/version")
  void secondResumeAfterReSuspension_usesNewVersion() {
    java.util.List<Long> observedVersions = new java.util.ArrayList<>();

    DurableExecutionEngine engine =
        new FakeDurableEngine() {
          @Override
          public AgentResult resumeProcess(
              String processId, long checkpointVersion, ContinuationSignal signal) {
            observedVersions.add(checkpointVersion);

            if (checkpointVersion == 1L) {
              // v1 → re-suspend to v2
              DurableResumeStrategy newStrategy =
                  new DurableResumeStrategy(processId, 2L, this);
              AgentProcess newProcess = DefaultAgentProcess.withStrategy(processId, newStrategy);
              return new AgentResult("partial", List.of(), newProcess);
            } else {
              // v2 → complete
              return new AgentResult("completed", List.of());
            }
          }
        };

    DurableResumeStrategy strategy = new DurableResumeStrategy("process-1", 1L, engine);
    AgentProcess process = DefaultAgentProcess.withStrategy("process-1", strategy);

    // First resume with v1
    process.resume(new ContinuationSignal.ApprovalSignal(true, "approved"));

    // Second resume with v2
    process.resume(new ContinuationSignal.ApprovalSignal(true, "approved"));

    assertThat(observedVersions).containsExactly(1L, 2L);
  }

  /**
   * Invariant #11: Second call after terminal failure does not invoke backend (regression).
   */
  @Test
  @DisplayName("Second call after FAILED does not invoke backend")
  void secondCallAfterFailed_doesNotInvokeBackend() {
    AtomicInteger engineCallCount = new AtomicInteger(0);

    DurableExecutionEngine engine =
        new FakeDurableEngine() {
          @Override
          public AgentResult resumeProcess(
              String processId, long checkpointVersion, ContinuationSignal signal) {
            engineCallCount.incrementAndGet();
            throw new RuntimeException("Backend failure");
          }
        };

    DurableResumeStrategy strategy = new DurableResumeStrategy("process-1", 1L, engine);
    AgentProcess process = DefaultAgentProcess.withStrategy("process-1", strategy);

    // First call → FAILED
    assertThatThrownBy(() -> process.resume(new ContinuationSignal.ApprovalSignal(true, "approved")))
        .isInstanceOf(RuntimeException.class);

    assertThat(process.status()).isEqualTo(ProcessStatus.FAILED);
    assertThat(engineCallCount.get()).isEqualTo(1);

    // Second call → blocked at CAS, backend NOT invoked
    assertThatThrownBy(() -> process.resume(new ContinuationSignal.ApprovalSignal(true, "approved")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Cannot resume process in state FAILED");

    assertThat(engineCallCount.get())
        .as("Backend should NOT be invoked after terminal FAILED")
        .isEqualTo(1);
  }
}
