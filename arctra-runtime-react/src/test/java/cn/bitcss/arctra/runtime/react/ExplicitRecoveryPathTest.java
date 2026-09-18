package cn.bitcss.arctra.runtime.react;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.bitcss.arctra.agent.AgentDefinition;
import cn.bitcss.arctra.agent.AgentExecutionContext;
import cn.bitcss.arctra.agent.AgentRequest;
import cn.bitcss.arctra.agent.AgentResult;
import cn.bitcss.arctra.checkpoint.CheckpointStore;
import cn.bitcss.arctra.checkpoint.PendingToolCall;
import static cn.bitcss.arctra.checkpoint.CheckpointTestHelper.*;
import cn.bitcss.arctra.checkpoint.ContinuationDisposition;
import cn.bitcss.arctra.checkpoint.SuspensionCheckpoint;
import java.util.Optional;
import cn.bitcss.arctra.evidence.Evidence;
import cn.bitcss.arctra.execution.ExecutionEvent;
import cn.bitcss.arctra.execution.ExecutionEventListener;
import cn.bitcss.arctra.process.ContinuationSignal;
import cn.bitcss.arctra.recovery.RecoveryUncertaintyException;
import cn.bitcss.arctra.runtime.DurableExecutionEngine;
import cn.bitcss.arctra.runtime.RuntimeBinding;
import cn.bitcss.arctra.runtime.RuntimeBindingResolver;
import cn.bitcss.arctra.runtime.react.durable.DurableResumeCoordinator;
import cn.bitcss.arctra.runtime.react.durable.InMemoryInvocationStateStore;
import cn.bitcss.arctra.runtime.react.durable.InvocationAttempt;
import cn.bitcss.arctra.runtime.react.durable.InvocationRecoveryClassifier;
import cn.bitcss.arctra.runtime.react.durable.InvocationStateStore;
import cn.bitcss.arctra.runtime.react.durable.RecoveryClassificationResult;
import cn.bitcss.arctra.runtime.react.protocol.ResumedExecutionHandler;
import cn.bitcss.arctra.runtime.react.protocol.ResumedExecutionOutcome;
import cn.bitcss.arctra.runtime.react.tool.ToolObservationContext;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Tests for explicit recovery classification pathway.
 *
 * <p><strong>M6-T4C.1: Executable recovery path closure.</strong>
 *
 * <p>Direct behavioral proof that explicit recovery path correctly classifies operations and
 * enforces safety invariants.
 *
 * @author lov3r
 * @since M6-T4C.1
 */
class ExplicitRecoveryPathTest {

  // Test 4: Explicit Recovery APPROVE, Safe Batch

  @Test
  void explicitRecovery_safeBatch_shouldExecuteBothOperations() {
    // Given - recording store with NO intents
    RecordingInvocationStateStore store = new RecordingInvocationStateStore();

    // Given - checkpoint with two operations
    PendingToolCall opA = new PendingToolCall("op-A", "tc-A", "tool-A", "{}");
    PendingToolCall opB = new PendingToolCall("op-B", "tc-B", "tool-B", "{}");

    SuspensionCheckpoint checkpoint =
        checkpoint(            "proc-test",
            1L,
            "binding-test",
            "session-test",
            List.of(opA, opB),
            List.of(),
            "epoch-original");

    TestCheckpointStore checkpointStore = new TestCheckpointStore();
    checkpointStore.create(checkpoint);

    // Given - runtime binding
    RuntimeBindingResolver bindingResolver =
        (processId, bindingKey, sessionId) ->
            new RuntimeBinding(
                new AgentDefinition("agent-test", "test"),
                AgentExecutionContext.withSession("session-test"));

    // Given - resumed execution handler that counts delegate calls
    AtomicInteger delegateCallCount = new AtomicInteger(0);
    ResumedExecutionHandler handler = createCountingHandler(delegateCallCount, store);

    // Given - coordinator with recovery classifier
    InvocationRecoveryClassifier classifier = new InvocationRecoveryClassifier(store);
    DurableResumeCoordinator coordinator =
        new DurableResumeCoordinator(
            checkpointStore,
            bindingResolver,
            event -> {},
            handler,
            createTestEngine(),
            classifier);

    // When - invoke EXPLICIT recovery path with APPROVE
    AgentResult result =
        coordinator.resume(
            "proc-test", 1L, new ContinuationSignal.ApprovalSignal(true, "test"), "epoch-current");

    // Then - both operations classified as safe
    assertThat(store.readCalls)
        .as("Should read intent for both operations")
        .containsExactlyInAnyOrder("proc-test:op-A", "proc-test:op-B");

    // Then - both operations executed
    assertThat(delegateCallCount.get())
        .as("Both safe operations should execute")
        .isEqualTo(2);

    // Then - write gate still enforced (recordInvocationIntent called)
    assertThat(store.writeCalls)
        .as("Write gate still mandatory before physical invocation")
        .containsExactlyInAnyOrder("proc-test:op-A", "proc-test:op-B");

    // Then - checkpoint deleted (CHECK B succeeded)
    assertThat(checkpointStore.exists("proc-test"))
        .as("Checkpoint should be deleted after successful execution")
        .isFalse();
  }

  // Test 5: Explicit Recovery APPROVE, Uncertain Operation

  @Test
  void explicitRecovery_uncertainBatch_shouldFailClosedBeforeAnyExecution() {
    // Given - store with intent for op-B only
    RecordingInvocationStateStore store = new RecordingInvocationStateStore();
    store.recordInvocationIntent("proc-test", "op-B", "attempt-test"); // op-B already invoked

    // Given - checkpoint with two operations (op-A safe, op-B uncertain)
    PendingToolCall opA = new PendingToolCall("op-A", "tc-A", "tool-A", "{}");
    PendingToolCall opB = new PendingToolCall("op-B", "tc-B", "tool-B", "{}");

    SuspensionCheckpoint checkpoint =
        checkpoint(            "proc-test",
            1L,
            "binding-test",
            "session-test",
            List.of(opA, opB),
            List.of(),
            "epoch-original");

    TestCheckpointStore checkpointStore = new TestCheckpointStore();
    checkpointStore.create(checkpoint);

    RuntimeBindingResolver bindingResolver =
        (processId, bindingKey, sessionId) ->
            new RuntimeBinding(
                new AgentDefinition("agent-test", "test"),
                AgentExecutionContext.withSession("session-test"));

    AtomicInteger delegateCallCount = new AtomicInteger(0);
    ResumedExecutionHandler handler = createCountingHandler(delegateCallCount, store);

    InvocationRecoveryClassifier classifier = new InvocationRecoveryClassifier(store);
    DurableResumeCoordinator coordinator =
        new DurableResumeCoordinator(
            checkpointStore,
            bindingResolver,
            event -> {},
            handler,
            createTestEngine(),
            classifier);

    // When/Then - explicit recovery with uncertain operation
    assertThatThrownBy(
            () ->
                coordinator.resume(
                    "proc-test", 1L, new ContinuationSignal.ApprovalSignal(true, "test"), "epoch-current"))
        .isInstanceOf(RecoveryUncertaintyException.class)
        .hasMessageContaining("op-B")
        .hasMessageContaining("unresolved physical attempt");

    // Then - CRITICAL: zero physical invocations (batch preflight prevented ALL execution)
    assertThat(delegateCallCount.get())
        .as("Uncertain operation must prevent ALL operations from executing")
        .isZero();

    // Then - checkpoint preserved (CHECK B not reached)
    assertThat(checkpointStore.exists("proc-test"))
        .as("Checkpoint must be preserved on uncertainty")
        .isTrue();

    assertThat(checkpointStore.load("proc-test").get().checkpointVersion())
        .as("Checkpoint version unchanged")
        .isEqualTo(1L);
  }

  // Test 6: Uncertain Operation First (order independence)

  @Test
  void explicitRecovery_uncertainOperationFirst_shouldStillFailClosed() {
    // Given - store with intent for op-A (first operation)
    RecordingInvocationStateStore store = new RecordingInvocationStateStore();
    store.recordInvocationIntent("proc-test", "op-A", "attempt-test"); // First op uncertain

    PendingToolCall opA = new PendingToolCall("op-A", "tc-A", "tool-A", "{}");
    PendingToolCall opB = new PendingToolCall("op-B", "tc-B", "tool-B", "{}");

    SuspensionCheckpoint checkpoint =
        checkpoint(            "proc-test",
            1L,
            "binding-test",
            "session-test",
            List.of(opA, opB), // op-A first
            List.of(),
            "epoch-original");

    TestCheckpointStore checkpointStore = new TestCheckpointStore();
    checkpointStore.create(checkpoint);

    RuntimeBindingResolver bindingResolver =
        (processId, bindingKey, sessionId) ->
            new RuntimeBinding(
                new AgentDefinition("agent-test", "test"),
                AgentExecutionContext.withSession("session-test"));

    AtomicInteger delegateCallCount = new AtomicInteger(0);
    ResumedExecutionHandler handler = createCountingHandler(delegateCallCount, store);

    InvocationRecoveryClassifier classifier = new InvocationRecoveryClassifier(store);
    DurableResumeCoordinator coordinator =
        new DurableResumeCoordinator(
            checkpointStore,
            bindingResolver,
            event -> {},
            handler,
            createTestEngine(),
            classifier);

    // When/Then - uncertain first operation
    assertThatThrownBy(
            () ->
                coordinator.resume(
                    "proc-test", 1L, new ContinuationSignal.ApprovalSignal(true, "test"), "epoch-current"))
        .isInstanceOf(RecoveryUncertaintyException.class)
        .hasMessageContaining("op-A");

    // Then - zero executions (order independence)
    assertThat(delegateCallCount.get())
        .as("Order should not matter - ALL operations blocked")
        .isZero();

    assertThat(checkpointStore.exists("proc-test"))
        .as("Checkpoint preserved")
        .isTrue();
  }

  // Test 7: Recovery Read Failure

  @Test
  void explicitRecovery_readFailure_shouldFailClosedAndPreserveCheckpoint() {
    // Given - failing store
    InvocationStateStore failingStore =
        new InvocationStateStore() {
          @Override
          public void recordInvocationIntent(String processId, String operationId, String attemptId) {
            // Writes work
          }

          @Override
          public boolean hasInvocationIntent(String processId, String operationId, String attemptId) {
            // Not used in M6-T5 (uses findAttempts instead)
            return false;
          }

          @Override
          public java.util.List<InvocationAttempt> findAttempts(String processId, String operationId) {
            throw new RuntimeException("Test storage read failure");
          }

          @Override
          public void recordResolution(
              String processId,
              String operationId,
              String attemptId,
              cn.bitcss.arctra.recovery.ResolutionType type,
              String recoveredResult) {
            // No-op
          }

          @Override
          public java.util.Optional<cn.bitcss.arctra.recovery.OperationResolution> getResolution(
              String processId, String operationId, String attemptId) {
            return java.util.Optional.empty();
          }
        };

    PendingToolCall opA = new PendingToolCall("op-A", "tc-A", "tool-A", "{}");

    SuspensionCheckpoint checkpoint =
        checkpoint(            "proc-test",
            1L,
            "binding-test",
            "session-test",
            List.of(opA),
            List.of(),
            "epoch-original");

    TestCheckpointStore checkpointStore = new TestCheckpointStore();
    checkpointStore.create(checkpoint);

    RuntimeBindingResolver bindingResolver =
        (processId, bindingKey, sessionId) ->
            new RuntimeBinding(
                new AgentDefinition("agent-test", "test"),
                AgentExecutionContext.withSession("session-test"));

    AtomicInteger delegateCallCount = new AtomicInteger(0);
    RecordingInvocationStateStore writeStore = new RecordingInvocationStateStore();
    ResumedExecutionHandler handler = createCountingHandler(delegateCallCount, writeStore);

    InvocationRecoveryClassifier classifier = new InvocationRecoveryClassifier(failingStore);
    DurableResumeCoordinator coordinator =
        new DurableResumeCoordinator(
            checkpointStore,
            bindingResolver,
            event -> {},
            handler,
            createTestEngine(),
            classifier);

    // When/Then - read failure during classification
    assertThatThrownBy(
            () ->
                coordinator.resume(
                    "proc-test", 1L, new ContinuationSignal.ApprovalSignal(true, "test"), "epoch-current"))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("Test storage read failure");

    // Then - zero physical invocations
    assertThat(delegateCallCount.get())
        .as("Read failure must prevent physical invocation")
        .isZero();

    // Then - zero write-gate calls (never reached execution)
    assertThat(writeStore.writeCalls)
        .as("Write gate not reached on read failure")
        .isEmpty();

    // Then - checkpoint preserved
    assertThat(checkpointStore.exists("proc-test"))
        .as("Checkpoint preserved on read failure")
        .isTrue();
  }

  // Test 8: REJECT With Existing Intent

  @Test
  void explicitRecovery_reject_shouldBypassClassificationAndSynthesizeRejection() {
    // Given - store with existing intent
    RecordingInvocationStateStore store = new RecordingInvocationStateStore();
    store.recordInvocationIntent("proc-test", "op-A", "attempt-test"); // Intent exists
    store.clearReadCalls(); // Clear any reads from setup

    PendingToolCall opA = new PendingToolCall("op-A", "tc-A", "tool-A", "{}");

    SuspensionCheckpoint checkpoint =
        checkpoint(            "proc-test",
            1L,
            "binding-test",
            "session-test",
            List.of(opA),
            List.of(),
            "epoch-original");

    TestCheckpointStore checkpointStore = new TestCheckpointStore();
    checkpointStore.create(checkpoint);

    RuntimeBindingResolver bindingResolver =
        (processId, bindingKey, sessionId) ->
            new RuntimeBinding(
                new AgentDefinition("agent-test", "test"),
                AgentExecutionContext.withSession("session-test"));

    AtomicInteger delegateCallCount = new AtomicInteger(0);
    ResumedExecutionHandler handler = createCountingHandler(delegateCallCount, store);

    InvocationRecoveryClassifier classifier = new InvocationRecoveryClassifier(store);
    DurableResumeCoordinator coordinator =
        new DurableResumeCoordinator(
            checkpointStore,
            bindingResolver,
            event -> {},
            handler,
            createTestEngine(),
            classifier);

    // When - REJECT signal (explicit recovery path)
    AgentResult result =
        coordinator.resume(
            "proc-test", 1L, new ContinuationSignal.ApprovalSignal(false, "test rejection"), "epoch-current");

    // Then - NO classification reads (REJECT bypasses classification gate)
    assertThat(store.readCalls)
        .as("REJECT should bypass recovery classification entirely")
        .isEmpty();

    // Then - zero physical invocations (rejection is synthetic)
    assertThat(delegateCallCount.get())
        .as("REJECT synthesizes responses without physical invocation")
        .isZero();

    // Then - checkpoint deleted (existing CHECK B behavior)
    assertThat(checkpointStore.exists("proc-test"))
        .as("REJECT deletes checkpoint per existing behavior")
        .isFalse();
  }

  // Helper: Recording InvocationStateStore

  private static class RecordingInvocationStateStore implements InvocationStateStore {
    private final InMemoryInvocationStateStore delegate = new InMemoryInvocationStateStore();
    final List<String> readCalls = new ArrayList<>();
    final List<String> writeCalls = new ArrayList<>();

    @Override
    public void recordInvocationIntent(String processId, String operationId, String attemptId) {
      writeCalls.add(processId + ":" + operationId);
      delegate.recordInvocationIntent(processId, operationId, attemptId);
    }

    @Override
    public boolean hasInvocationIntent(String processId, String operationId, String attemptId) {
      readCalls.add(processId + ":" + operationId);
      return delegate.hasInvocationIntent(processId, operationId, attemptId);
    }

    @Override
    public java.util.List<cn.bitcss.arctra.runtime.react.durable.InvocationAttempt> findAttempts(
        String processId, String operationId) {
      readCalls.add(processId + ":" + operationId);
      return delegate.findAttempts(processId, operationId);
    }

    @Override
    public void recordResolution(
        String processId,
        String operationId,
        String attemptId,
        cn.bitcss.arctra.recovery.ResolutionType type,
        String recoveredResult) {
      delegate.recordResolution(processId, operationId, attemptId, type, recoveredResult);
    }

    @Override
    public java.util.Optional<cn.bitcss.arctra.recovery.OperationResolution> getResolution(
        String processId, String operationId, String attemptId) {
      return delegate.getResolution(processId, operationId, attemptId);
    }

    void clearReadCalls() {
      readCalls.clear();
    }
  }

  // Helper: Test Checkpoint Store

  private static class TestCheckpointStore implements CheckpointStore {
    private SuspensionCheckpoint stored;

    @Override
    public void create(SuspensionCheckpoint checkpoint) {
      this.stored = checkpoint;
    }

    @Override
    public Optional<SuspensionCheckpoint> load(String processId) {
      if (stored == null || !stored.processId().equals(processId)) {
        return Optional.empty();
      }
      return Optional.of(stored);
    }

    @Override
    public boolean deleteIfVersion(String processId, long expectedVersion) {
      if (stored != null
          && stored.processId().equals(processId)
          && stored.checkpointVersion() == expectedVersion) {
        stored = null;
        return true;
      }
      return false;
    }

    @Override
    public boolean replaceIfVersion(
        String processId, long expectedVersion, SuspensionCheckpoint newCheckpoint) {
      if (stored != null
          && stored.processId().equals(processId)
          && stored.checkpointVersion() == expectedVersion) {
        stored = newCheckpoint;
        return true;
      }
      return false;
    }

    boolean exists(String processId) {
      return stored != null && stored.processId().equals(processId);
    }
  }

  // Helper: Counting Handler

  private ResumedExecutionHandler createCountingHandler(
      AtomicInteger counter, InvocationStateStore store) {
    return new ResumedExecutionHandler() {
      @Override
      public ResumedExecutionOutcome executeResume(
          List<PendingToolCall> pendingBatch,
          RuntimeBinding binding,
          List<Evidence> historicalEvidences,
          ContinuationSignal signal,
          ToolObservationContext baseObservationContext,
          List<RecoveryClassificationResult> classifications) {

        if (signal instanceof ContinuationSignal.ApprovalSignal approval && approval.approved()) {
          // Simulate physical execution for APPROVE
          for (PendingToolCall op : pendingBatch) {
            // Record intent (write gate)
            store.recordInvocationIntent(baseObservationContext.processId(), op.operationId(), "attempt-test");
            // Physical invocation
            counter.incrementAndGet();
          }
          // Success outcome
          return new ResumedExecutionOutcome.ModelCompleted("completed", List.of());
        } else {
          // REJECT - synthetic rejection, no physical invocation
          return new ResumedExecutionOutcome.ModelCompleted("rejected", List.of());
        }
      }

      @Override
      public void persistCompletedAssistant(AgentExecutionContext context, String content) {
        // No-op for unit tests
      }
    };
  }

  // Helper: Test Engine

  private DurableExecutionEngine createTestEngine() {
    return new DurableExecutionEngine() {
      @Override
      public AgentResult execute(
          AgentDefinition definition, AgentRequest request, AgentExecutionContext context) {
        throw new UnsupportedOperationException("Test engine should not be called");
      }

      @Override
      public AgentResult resumeProcess(
          String processId, long checkpointVersion, ContinuationSignal signal) {
        throw new UnsupportedOperationException("Test engine should not be called");
      }

      @Override
      public cn.bitcss.arctra.runtime.RecoveryResolution recovery() {
        throw new UnsupportedOperationException("Test engine should not be called");
      }
    };
  }
}
