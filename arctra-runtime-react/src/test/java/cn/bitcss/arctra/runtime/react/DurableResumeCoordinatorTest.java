package cn.bitcss.arctra.runtime.react;

import static org.assertj.core.api.Assertions.*;

import cn.bitcss.arctra.agent.AgentDefinition;
import cn.bitcss.arctra.agent.AgentExecutionContext;
import cn.bitcss.arctra.agent.AgentResult;
import cn.bitcss.arctra.checkpoint.CheckpointStore;
import cn.bitcss.arctra.checkpoint.PendingToolCall;
import cn.bitcss.arctra.checkpoint.SuspensionCheckpoint;
import cn.bitcss.arctra.evidence.Evidence;
import cn.bitcss.arctra.execution.EventType;
import cn.bitcss.arctra.execution.ExecutionEvent;
import cn.bitcss.arctra.execution.ExecutionEventListener;
import cn.bitcss.arctra.process.ContinuationSignal;
import cn.bitcss.arctra.runtime.RuntimeBinding;
import cn.bitcss.arctra.runtime.RuntimeBindingResolver;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for DurableResumeCoordinator.
 *
 * <p>Proves that durable resume orchestration can be tested without Spring AI objects.
 *
 * @author lov3r
 * @since M6-T2.5A-R4.1
 */
@DisplayName("DurableResumeCoordinator")
class DurableResumeCoordinatorTest {

  @Test
  @DisplayName("Missing checkpoint throws CheckpointNotFoundException")
  void missingCheckpoint() {
    var store = new TestCheckpointStore();
    var resolver = TestBindings.standardResolver();
    var events = new TestEventListener();
    var handler = new TestResumedExecutionHandler();
    var engine = new TestDurableExecutionEngine();

    var coordinator = new DurableResumeCoordinator(store, resolver, events, handler, engine,
        new InvocationRecoveryClassifier(new InMemoryInvocationStateStore()));

    assertThatThrownBy(
            () ->
                coordinator.resume(
                    "missing-process", 1L, new ContinuationSignal.ApprovalSignal(true, "user"),
                    ExecutionIncarnation.current()))
        .isInstanceOf(cn.bitcss.arctra.checkpoint.CheckpointNotFoundException.class)
        .hasMessageContaining("missing-process");

    assertThat(handler.invocationCount).isZero();
    assertThat(events.events).isEmpty();
  }

  @Test
  @DisplayName("Stale checkpoint version throws StaleCheckpointException")
  void staleCheckpointVersion() {
    var store = new TestCheckpointStore();
    var checkpoint = TestCheckpoints.withVersion("process-1", 5L);
    store.create(checkpoint);

    var resolver = TestBindings.standardResolver();
    var events = new TestEventListener();
    var handler = new TestResumedExecutionHandler();
    var engine = new TestDurableExecutionEngine();

    var coordinator = new DurableResumeCoordinator(store, resolver, events, handler, engine,
        new InvocationRecoveryClassifier(new InMemoryInvocationStateStore()));

    assertThatThrownBy(
            () ->
                coordinator.resume(
                    "process-1", 3L, new ContinuationSignal.ApprovalSignal(true, "user"),
                    ExecutionIncarnation.current()))
        .isInstanceOf(cn.bitcss.arctra.checkpoint.StaleCheckpointException.class)
        .hasMessageContaining("3")
        .hasMessageContaining("5");

    assertThat(handler.invocationCount).isZero();
    assertThat(events.events).isEmpty();
  }

  @Test
  @DisplayName("Binding resolution failure throws ResumePreparationException")
  void bindingResolutionFailure() {
    var store = new TestCheckpointStore();
    var checkpoint = TestCheckpoints.withBindingKey("process-1", 1L, "binding-key-1", "session-1");
    store.create(checkpoint);

    var resolver = new TestFailingBindingResolver();
    var events = new TestEventListener();
    var handler = new TestResumedExecutionHandler();
    var engine = new TestDurableExecutionEngine();

    var coordinator = new DurableResumeCoordinator(store, resolver, events, handler, engine,
        new InvocationRecoveryClassifier(new InMemoryInvocationStateStore()));

    assertThatThrownBy(
            () ->
                coordinator.resume(
                    "process-1", 1L, new ContinuationSignal.ApprovalSignal(true, "user"),
                    ExecutionIncarnation.current()))
        .isInstanceOf(cn.bitcss.arctra.runtime.ResumePreparationException.class)
        .hasMessageContaining("process-1")
        .hasMessageContaining("binding-key-1");

    // Verify resolver received checkpoint identity
    assertThat(resolver.lastProcessId).isEqualTo("process-1");
    assertThat(resolver.lastBindingKey).isEqualTo("binding-key-1");
    assertThat(resolver.lastSessionId).isEqualTo("session-1");

    assertThat(handler.invocationCount).isZero();
    assertThat(events.events).isEmpty();
    assertThat(store.load("process-1")).isPresent();
  }

  @Test
  @DisplayName("Approved signal emits APPROVAL_GRANTED → RESUMED → COMPLETED")
  void approvedSignalFlow() {
    var store = new TestCheckpointStore();
    var checkpoint = TestCheckpoints.withVersion("process-1", 1L);
    store.create(checkpoint);

    var resolver = TestBindings.standardResolver();
    var events = new TestEventListener();
    var handler = new TestResumedExecutionHandler();
    var engine = new TestDurableExecutionEngine();

    handler.nextOutcome = new ResumedExecutionOutcome.ModelCompleted("final content", List.of());

    var coordinator = new DurableResumeCoordinator(store, resolver, events, handler, engine,
        new InvocationRecoveryClassifier(new InMemoryInvocationStateStore()));

    AgentResult result =
        coordinator.resume("process-1", 1L, new ContinuationSignal.ApprovalSignal(true, "user"),
            ExecutionIncarnation.current());

    assertThat(events.eventTypes())
        .containsExactly(EventType.APPROVAL_GRANTED, EventType.RESUMED, EventType.COMPLETED);
    assertThat(handler.invocationCount).isOne();
    assertThat(result.content()).isEqualTo("final content");
    assertThat(result.process()).isNull();
  }

  @Test
  @DisplayName("Rejected signal emits APPROVAL_REJECTED → RESUMED → COMPLETED")
  void rejectedSignalFlow() {
    var store = new TestCheckpointStore();
    var checkpoint = TestCheckpoints.withVersion("process-1", 1L);
    store.create(checkpoint);

    var resolver = TestBindings.standardResolver();
    var events = new TestEventListener();
    var handler = new TestResumedExecutionHandler();
    var engine = new TestDurableExecutionEngine();

    handler.nextOutcome = new ResumedExecutionOutcome.ModelCompleted("denial response", List.of());

    var coordinator = new DurableResumeCoordinator(store, resolver, events, handler, engine,
        new InvocationRecoveryClassifier(new InMemoryInvocationStateStore()));

    AgentResult result =
        coordinator.resume("process-1", 1L, new ContinuationSignal.ApprovalSignal(false, "user"),
            ExecutionIncarnation.current());

    assertThat(events.eventTypes())
        .containsExactly(EventType.APPROVAL_REJECTED, EventType.RESUMED, EventType.COMPLETED);
    assertThat(handler.invocationCount).isOne();
    assertThat(result.content()).isEqualTo("denial response");
  }

  @Test
  @DisplayName("Completion success performs CHECK B deleteIfVersion")
  void completionSuccess() {
    var store = new TestCheckpointStore();
    var checkpoint = TestCheckpoints.withVersion("process-1", 1L);
    store.create(checkpoint);

    var resolver = TestBindings.standardResolver();
    var events = new TestEventListener();
    var handler = new TestResumedExecutionHandler();
    var engine = new TestDurableExecutionEngine();

    var evidences = List.of(new Evidence("test", "evidence"));
    handler.nextOutcome = new ResumedExecutionOutcome.ModelCompleted("final", evidences);

    var coordinator = new DurableResumeCoordinator(store, resolver, events, handler, engine,
        new InvocationRecoveryClassifier(new InMemoryInvocationStateStore()));

    AgentResult result =
        coordinator.resume("process-1", 1L, new ContinuationSignal.ApprovalSignal(true, "user"),
            ExecutionIncarnation.current());

    // CHECK B deleted checkpoint
    assertThat(store.load("process-1")).isNotPresent();
    assertThat(events.hasEvent(EventType.COMPLETED)).isTrue();
    assertThat(result.evidences()).isEqualTo(evidences);
  }

  @Test
  @DisplayName("Completion conflict emits CHECKPOINT_CONFLICT")
  void completionConflict() {
    var store = new TestCheckpointStore();
    store.deleteIfVersionShouldSucceed = false;

    var checkpoint = TestCheckpoints.withVersion("process-1", 1L);
    store.create(checkpoint);

    var resolver = TestBindings.standardResolver();
    var events = new TestEventListener();
    var handler = new TestResumedExecutionHandler();
    var engine = new TestDurableExecutionEngine();

    handler.nextOutcome = new ResumedExecutionOutcome.ModelCompleted("final", List.of());

    var coordinator = new DurableResumeCoordinator(store, resolver, events, handler, engine,
        new InvocationRecoveryClassifier(new InMemoryInvocationStateStore()));

    assertThatThrownBy(
            () ->
                coordinator.resume(
                    "process-1", 1L, new ContinuationSignal.ApprovalSignal(true, "user"),
                    ExecutionIncarnation.current()))
        .isInstanceOf(cn.bitcss.arctra.checkpoint.CheckpointTransitionConflictException.class);

    assertThat(events.hasEvent(EventType.CHECKPOINT_CONFLICT)).isTrue();
    assertThat(events.hasEvent(EventType.COMPLETED)).isFalse();
  }

  @Test
  @DisplayName("Re-suspension preserves checkpoint identity")
  void reSuspensionPreservesIdentity() {
    var store = new TestCheckpointStore();
    var oldCheckpoint =
        TestCheckpoints.withBindingKeyAndEvidences(
            "process-1",
            1L,
            "binding-key-1",
            "session-1",
            List.of(new Evidence("old", "evidence")));
    store.create(oldCheckpoint);

    var resolver = TestBindings.standardResolver();
    var events = new TestEventListener();
    var handler = new TestResumedExecutionHandler();
    var engine = new TestDurableExecutionEngine();

    var newPendingBatch =
        List.of(
            new PendingToolCall("test-op-2", "call-2", "tool2", "{}"),
            new PendingToolCall("test-op-3", "call-3", "tool3", "{}"));
    var newEvidences =
        List.of(new Evidence("old", "evidence"), new Evidence("new", "evidence"));
    handler.nextOutcome =
        new ResumedExecutionOutcome.GovernanceSuspended(newPendingBatch, newEvidences);

    var coordinator = new DurableResumeCoordinator(store, resolver, events, handler, engine,
        new InvocationRecoveryClassifier(new InMemoryInvocationStateStore()));

    AgentResult result =
        coordinator.resume("process-1", 1L, new ContinuationSignal.ApprovalSignal(true, "user"),
            ExecutionIncarnation.current());

    // Verify event sequence
    assertThat(events.eventTypes())
        .containsExactly(
            EventType.APPROVAL_GRANTED,
            EventType.RESUMED,
            EventType.APPROVAL_REQUIRED,
            EventType.SUSPENDED);

    // Verify next checkpoint preserved identity
    SuspensionCheckpoint nextCheckpoint = store.load("process-1").orElseThrow();
    assertThat(nextCheckpoint.processId()).isEqualTo("process-1");
    assertThat(nextCheckpoint.checkpointVersion()).isEqualTo(2L);
    assertThat(nextCheckpoint.runtimeBindingKey()).isEqualTo("binding-key-1"); // Preserved
    assertThat(nextCheckpoint.sessionId()).isEqualTo("session-1"); // Preserved
    assertThat(nextCheckpoint.pendingBatch()).isEqualTo(newPendingBatch);
    assertThat(nextCheckpoint.accumulatedEvidences()).isEqualTo(newEvidences);

    assertThat(result.process()).isNotNull();
  }

  @Test
  @DisplayName("Re-suspension conflict emits CHECKPOINT_CONFLICT")
  void reSuspensionConflict() {
    var store = new TestCheckpointStore();
    store.replaceIfVersionShouldSucceed = false;

    var checkpoint = TestCheckpoints.withVersion("process-1", 1L);
    store.create(checkpoint);

    var resolver = TestBindings.standardResolver();
    var events = new TestEventListener();
    var handler = new TestResumedExecutionHandler();
    var engine = new TestDurableExecutionEngine();

    handler.nextOutcome =
        new ResumedExecutionOutcome.GovernanceSuspended(
            List.of(new PendingToolCall("test-op-2", "call-2", "tool2", "{}")), List.of());

    var coordinator = new DurableResumeCoordinator(store, resolver, events, handler, engine,
        new InvocationRecoveryClassifier(new InMemoryInvocationStateStore()));

    assertThatThrownBy(
            () ->
                coordinator.resume(
                    "process-1", 1L, new ContinuationSignal.ApprovalSignal(true, "user"),
                    ExecutionIncarnation.current()))
        .isInstanceOf(cn.bitcss.arctra.checkpoint.CheckpointTransitionConflictException.class);

    assertThat(events.hasEvent(EventType.APPROVAL_REQUIRED)).isTrue();
    assertThat(events.hasEvent(EventType.CHECKPOINT_CONFLICT)).isTrue();
    assertThat(events.hasEvent(EventType.SUSPENDED)).isFalse();
  }

  @Test
  @DisplayName("Provider failure does not perform CHECK B")
  void providerFailure() {
    var store = new TestCheckpointStore();
    var checkpoint = TestCheckpoints.withVersion("process-1", 1L);
    store.create(checkpoint);

    var resolver = TestBindings.standardResolver();
    var events = new TestEventListener();
    var handler = new TestResumedExecutionHandler();
    var engine = new TestDurableExecutionEngine();

    handler.shouldThrow = true;

    var coordinator = new DurableResumeCoordinator(store, resolver, events, handler, engine,
        new InvocationRecoveryClassifier(new InMemoryInvocationStateStore()));

    assertThatThrownBy(
            () ->
                coordinator.resume(
                    "process-1", 1L, new ContinuationSignal.ApprovalSignal(true, "user"),
                    ExecutionIncarnation.current()))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("Handler failure");

    // Checkpoint unchanged
    assertThat(store.load("process-1")).isPresent();

    // No completion/suspension events
    assertThat(events.hasEvent(EventType.COMPLETED)).isFalse();
    assertThat(events.hasEvent(EventType.SUSPENDED)).isFalse();
  }

  // === Test Fixtures ===

  static class TestCheckpointStore implements CheckpointStore {
    final Map<String, SuspensionCheckpoint> store = new ConcurrentHashMap<>();
    boolean deleteIfVersionShouldSucceed = true;
    boolean replaceIfVersionShouldSucceed = true;

    @Override
    public void create(SuspensionCheckpoint checkpoint) {
      store.put(checkpoint.processId(), checkpoint);
    }

    @Override
    public Optional<SuspensionCheckpoint> load(String processId) {
      return Optional.ofNullable(store.get(processId));
    }

    @Override
    public boolean deleteIfVersion(String processId, long expectedVersion) {
      if (!deleteIfVersionShouldSucceed) {
        return false;
      }
      var checkpoint = store.get(processId);
      if (checkpoint != null && checkpoint.checkpointVersion() == expectedVersion) {
        store.remove(processId);
        return true;
      }
      return false;
    }

    @Override
    public boolean replaceIfVersion(
        String processId, long expectedVersion, SuspensionCheckpoint newCheckpoint) {
      if (!replaceIfVersionShouldSucceed) {
        return false;
      }
      var old = store.get(processId);
      if (old != null && old.checkpointVersion() == expectedVersion) {
        store.put(processId, newCheckpoint);
        return true;
      }
      return false;
    }
  }

  static class TestFailingBindingResolver implements RuntimeBindingResolver {
    String lastProcessId;
    String lastBindingKey;
    String lastSessionId;

    @Override
    public RuntimeBinding resolve(String processId, String bindingKey, String sessionId) {
      lastProcessId = processId;
      lastBindingKey = bindingKey;
      lastSessionId = sessionId;
      throw new RuntimeException("Resolver failure");
    }
  }

  static class TestEventListener implements ExecutionEventListener {
    final List<ExecutionEvent> events = new ArrayList<>();

    @Override
    public void onEvent(ExecutionEvent event) {
      events.add(event);
    }

    List<EventType> eventTypes() {
      return events.stream().map(ExecutionEvent::eventType).toList();
    }

    boolean hasEvent(EventType type) {
      return events.stream().anyMatch(e -> e.eventType() == type);
    }
  }

  static class TestResumedExecutionHandler implements ResumedExecutionHandler {
    int invocationCount = 0;
    ResumedExecutionOutcome nextOutcome;
    boolean shouldThrow = false;

    @Override
    public ResumedExecutionOutcome executeResume(
        List<PendingToolCall> pendingBatch,
        RuntimeBinding binding,
        List<Evidence> checkpointEvidences,
        ContinuationSignal signal,
        ToolObservationContext observationContext,
        List<RecoveryClassificationResult> classifications) {

      invocationCount++;

      if (shouldThrow) {
        throw new RuntimeException("Handler failure");
      }

      return nextOutcome;
    }

    @Override
    public void persistCompletedAssistant(AgentExecutionContext context, String content) {
      // No-op for unit tests
    }
  }

  static class TestDurableExecutionEngine
      implements cn.bitcss.arctra.runtime.DurableExecutionEngine {
    @Override
    public AgentResult execute(
        AgentDefinition definition,
        cn.bitcss.arctra.agent.AgentRequest request,
        AgentExecutionContext context) {
      throw new UnsupportedOperationException();
    }

    @Override
    public AgentResult resumeProcess(
        String processId, long checkpointVersion, ContinuationSignal signal) {
      throw new UnsupportedOperationException();
    }

    @Override
    public cn.bitcss.arctra.runtime.RecoveryResolution recovery() {
      throw new UnsupportedOperationException();
    }
  }
}
