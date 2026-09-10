package cn.bitcss.arctra.runtime;

import static org.junit.jupiter.api.Assertions.*;

import cn.bitcss.arctra.agent.AgentDefinition;
import cn.bitcss.arctra.agent.AgentExecutionContext;
import cn.bitcss.arctra.agent.AgentRequest;
import cn.bitcss.arctra.agent.AgentResult;
import cn.bitcss.arctra.process.AgentProcess;
import cn.bitcss.arctra.process.ContinuationSignal;
import cn.bitcss.arctra.process.ProcessStatus;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Tests for Phase 2 durable capability abstractions.
 *
 * <p>Verifies:
 *
 * <ul>
 *   <li>DurableResumeStrategy lightweight prepare semantics
 *   <li>ProcessFactory.createDurableSuspended()
 *   <li>Same-handle concurrency protection
 *   <li>M4 ephemeral regression
 * </ul>
 *
 * @author lov3r
 * @since M5-T4
 */
class DurableCapabilityTest {

  @Test
  void durableStrategy_prepare_noSideEffects() {
    // Fake engine that records calls
    var callCounter = new AtomicInteger(0);
    DurableExecutionEngine fakeEngine = new FakeDurableEngine(callCounter);

    ResumeStrategy strategy = new DurableResumeStrategy("P100", 7L, fakeEngine);

    // Prepare should NOT execute engine
    ResumeAttempt attempt = strategy.prepare(testSignal());

    assertEquals(0, callCounter.get(), "prepare() should not call engine.resumeProcess()");
    assertNotNull(attempt, "prepare() should return attempt");

    // Execute should call engine exactly once
    attempt.execute();

    assertEquals(1, callCounter.get(), "execute() should call engine.resumeProcess() exactly once");
  }

  @Test
  void durableStrategy_execute_passesCorrectParameters() {
    var capturedProcessId = new String[1];
    var capturedVersion = new long[1];
    var capturedSignal = new ContinuationSignal[1];

    DurableExecutionEngine fakeEngine = new FakeDurableEngine(new AtomicInteger()) {
      @Override
      public AgentResult resumeProcess(
          String processId, long checkpointVersion, ContinuationSignal signal) {
        capturedProcessId[0] = processId;
        capturedVersion[0] = checkpointVersion;
        capturedSignal[0] = signal;
        return completedResult("done");
      }
    };

    ResumeStrategy strategy = new DurableResumeStrategy("P100", 7L, fakeEngine);
    ContinuationSignal signal = testSignal();

    ResumeAttempt attempt = strategy.prepare(signal);
    attempt.execute();

    assertEquals("P100", capturedProcessId[0], "processId should match");
    assertEquals(7L, capturedVersion[0], "checkpointVersion should match");
    assertSame(signal, capturedSignal[0], "signal should match");
  }

  @Test
  void processFactory_createDurableSuspended_returnsWaitingProcess() {
    DurableExecutionEngine fakeEngine = new FakeDurableEngine(new AtomicInteger());

    AgentProcess process = ProcessFactory.createDurableSuspended("P100", 7L, fakeEngine);

    assertNotNull(process, "Factory should return process");
    assertEquals("P100", process.id(), "Process ID should match");
    assertEquals(ProcessStatus.WAITING, process.status(), "Process should start in WAITING state");
  }

  @Test
  void processFactory_createDurableSuspended_nullEngine_throws() {
    assertThrows(
        NullPointerException.class,
        () -> ProcessFactory.createDurableSuspended("P100", 1L, null),
        "Null engine should throw");
  }

  @Test
  void processFactory_createDurableSuspended_nullProcessId_throws() {
    DurableExecutionEngine fakeEngine = new FakeDurableEngine(new AtomicInteger());

    assertThrows(
        NullPointerException.class,
        () -> ProcessFactory.createDurableSuspended(null, 1L, fakeEngine),
        "Null processId should throw");
  }

  @Test
  void sameHandleConcurrency_onlyOneExecutes() throws Exception {
    var executionCounter = new AtomicInteger(0);
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch executeLatch = new CountDownLatch(1);

    DurableExecutionEngine blockingEngine = new FakeDurableEngine(executionCounter) {
      @Override
      public AgentResult resumeProcess(
          String processId, long checkpointVersion, ContinuationSignal signal) {
        executionCounter.incrementAndGet();
        try {
          executeLatch.await(); // Block to ensure second thread tries to resume
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        return completedResult("done");
      }
    };

    AgentProcess process = ProcessFactory.createDurableSuspended("P100", 1L, blockingEngine);

    ExecutorService executor = Executors.newFixedThreadPool(2);

    // Thread 1: Will succeed
    Future<AgentResult> future1 =
        executor.submit(
            () -> {
              startLatch.await();
              return process.resume(testSignal());
            });

    // Thread 2: Will fail at CAS
    Future<AgentResult> future2 =
        executor.submit(
            () -> {
              startLatch.await();
              Thread.sleep(10); // Slight delay to ensure thread 1 wins CAS
              return process.resume(testSignal());
            });

    startLatch.countDown(); // Start both threads

    // Wait a bit for both threads to attempt resume
    Thread.sleep(50);

    // Thread 2 should fail at CAS (before execution)
    Exception exception = assertThrows(Exception.class, () -> future2.get());
    assertTrue(
        exception.getCause() instanceof IllegalStateException,
        "Second thread should fail with IllegalStateException");
    assertTrue(
        exception.getCause().getMessage().contains("RUNNING"),
        "Error should mention RUNNING state");

    // Allow first thread to complete
    executeLatch.countDown();
    AgentResult result1 = future1.get();

    assertNotNull(result1, "First thread should complete successfully");
    assertEquals(
        1, executionCounter.get(), "Engine should be called exactly once (only by first thread)");

    executor.shutdown();
  }

  @Test
  void m4EphemeralRegression_functionBasedSuspension_stillWorks() {
    // M4 ephemeral suspension using Function
    AgentProcess process =
        ProcessFactory.createSuspended(signal -> completedResult("ephemeral result"));

    assertEquals(ProcessStatus.WAITING, process.status(), "Should start WAITING");

    AgentResult result = process.resume(testSignal());

    assertEquals("ephemeral result", result.content(), "Should execute continuation");
    assertTrue(result.isCompleted(), "Should be completed");
    assertNull(result.process(), "Completed result should not have process");
    assertEquals(ProcessStatus.COMPLETED, process.status(), "Should transition to COMPLETED");
  }

  @Test
  void m4EphemeralRegression_noCheckpointStore_required() {
    // Ephemeral suspension should NOT require CheckpointStore or DurableExecutionEngine
    AgentProcess process = ProcessFactory.createSuspended(signal -> completedResult("done"));

    // Should work without any durable infrastructure
    AgentResult result = process.resume(testSignal());

    assertTrue(result.isCompleted(), "Ephemeral suspension should work without durable config");
  }

  // ===== Helper methods =====

  private ContinuationSignal testSignal() {
    return new ContinuationSignal.ApprovalSignal(true, "test-approval");
  }

  private AgentResult completedResult(String content) {
    return new AgentResult(content, List.of());
  }

  /**
   * Fake DurableExecutionEngine for testing.
   */
  private static class FakeDurableEngine implements DurableExecutionEngine {
    private final AtomicInteger callCounter;

    FakeDurableEngine(AtomicInteger callCounter) {
      this.callCounter = callCounter;
    }

    @Override
    public AgentResult resumeProcess(
        String processId, long checkpointVersion, ContinuationSignal signal) {
      callCounter.incrementAndGet();
      return new AgentResult("completed", List.of());
    }

    @Override
    public AgentResult execute(
        AgentDefinition definition, AgentRequest request, AgentExecutionContext context) {
      throw new UnsupportedOperationException("Not used in these tests");
    }
  }
}
