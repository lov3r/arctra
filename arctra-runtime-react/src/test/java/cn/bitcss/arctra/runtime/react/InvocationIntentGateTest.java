package cn.bitcss.arctra.runtime.react;

import static org.assertj.core.api.Assertions.*;

import cn.bitcss.arctra.checkpoint.PendingToolCall;
import cn.bitcss.arctra.evidence.Evidence;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.tool.ToolCallback;

/**
 * Tests for invocation intent gate semantics.
 *
 * <p>Proves that intent persistence failure blocks physical execution (hard gate).
 *
 * @author lov3r
 * @since M6-T4A
 */
class InvocationIntentGateTest {

  // MOST IMPORTANT TEST: Persistence failure blocks execution

  @Test
  void intentPersistenceFailure_shouldBlockDelegateExecution() {
    // Given - failing store
    InvocationStateStore failingStore =
        new InvocationStateStore() {
          @Override
          public void recordInvocationIntent(String processId, String operationId, String attemptId) {
            throw new InvocationIntentPersistenceException(
                "Test persistence failure for process=" + processId + ", operation=" + operationId);
          }

          @Override
          public boolean hasInvocationIntent(String processId, String operationId, String attemptId) {
            // Not used in this test
            return false;
          }

          @Override
          public java.util.List<InvocationAttempt> findAttempts(String processId, String operationId) {
            return java.util.List.of();
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

    // Given - counting delegate to track invocations
    AtomicInteger delegateCallCount = new AtomicInteger(0);
    ToolCallback countingTool = TestTools.createCountingTool("test-tool", delegateCallCount);

    // Given - reconstructor with failing store
    ProtocolReconstructor reconstructor =
        new ProtocolReconstructor(List.of(countingTool), failingStore);

    // Given - pending operation
    PendingToolCall pendingOp =
        new PendingToolCall("op-123", "tc-123", "test-tool", "{}");

    List<Message> conversationHistory = List.of();
    List<Evidence> checkpointEvidences = List.of();
    List<Evidence> newEvidences = new ArrayList<>();

    ToolObservationContext observationContext =
        new ToolObservationContext(
            "proc-test",
            1L,
            "op-123",
            event -> {} // no-op listener
        );

    // When - execute with failing store
    assertThatThrownBy(
            () ->
                reconstructor.executeApprovedBatch(
                    List.of(pendingOp),
                    conversationHistory,
                    checkpointEvidences,
                    newEvidences,
                    observationContext,
                    List.of()))
        .isInstanceOf(InvocationIntentPersistenceException.class)
        .hasMessageContaining("Test persistence failure")
        .hasMessageContaining("proc-test")
        .hasMessageContaining("op-123");

    // Then - CRITICAL: delegate was NEVER called
    assertThat(delegateCallCount.get())
        .as("Delegate invocation count must be 0 (execution blocked by gate failure)")
        .isEqualTo(0);

    // Then - no evidence captured
    assertThat(newEvidences)
        .as("No evidence should be captured (delegate never executed)")
        .isEmpty();
  }

  @Test
  void intentPersistenceSuccess_shouldAllowDelegateExecution() {
    // Given - successful store
    InMemoryInvocationStateStore successfulStore = new InMemoryInvocationStateStore();

    // Given - counting delegate
    AtomicInteger delegateCallCount = new AtomicInteger(0);
    ToolCallback countingTool = TestTools.createCountingTool("test-tool", delegateCallCount);

    // Given - reconstructor with successful store
    ProtocolReconstructor reconstructor =
        new ProtocolReconstructor(List.of(countingTool), successfulStore);

    // Given - pending operation
    PendingToolCall pendingOp =
        new PendingToolCall("op-123", "tc-123", "test-tool", "{}");

    List<Message> conversationHistory = List.of();
    List<Evidence> checkpointEvidences = List.of();
    List<Evidence> newEvidences = new ArrayList<>();

    ToolObservationContext observationContext =
        new ToolObservationContext("proc-test", 1L, "op-123", event -> {});

    // When - execute with successful store
    List<Message> result =
        reconstructor.executeApprovedBatch(
            List.of(pendingOp),
            conversationHistory,
            checkpointEvidences,
            newEvidences,
            observationContext,
            List.of());

    // Then - delegate WAS called (after successful intent recording)
    assertThat(delegateCallCount.get())
        .as("Delegate should be invoked after successful intent recording")
        .isEqualTo(1);

    // Then - intent was recorded
    assertThat(successfulStore.hasInvocationIntent("proc-test", "op-123", "attempt-test"))
        .as("Intent should be recorded before execution")
        .isTrue();

    // Then - result constructed
    assertThat(result).as("Result messages should be constructed").isNotEmpty();
  }

  // Test: Intent recorded BEFORE delegate invocation (ordering)

  @Test
  void intentRecording_shouldOccurBeforeDelegateInvocation() {
    // Given - recording store that tracks order
    List<String> executionOrder = new ArrayList<>();

    InvocationStateStore recordingStore =
        new InvocationStateStore() {
          private final InMemoryInvocationStateStore delegate = new InMemoryInvocationStateStore();

          @Override
          public void recordInvocationIntent(String processId, String operationId, String attemptId) {
            executionOrder.add("INTENT_RECORDED:" + operationId);
            delegate.recordInvocationIntent(processId, operationId, attemptId);
          }

          @Override
          public boolean hasInvocationIntent(String processId, String operationId, String attemptId) {
            return delegate.hasInvocationIntent(processId, operationId, attemptId);
          }

          @Override
          public java.util.List<InvocationAttempt> findAttempts(String processId, String operationId) {
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
        };

    // Given - delegate that tracks calls
    ToolCallback recordingTool =
        TestTools.createRecordingTool("test-tool", executionOrder, "DELEGATE_CALLED");

    // Given - reconstructor
    ProtocolReconstructor reconstructor =
        new ProtocolReconstructor(List.of(recordingTool), recordingStore);

    // Given - pending operation
    PendingToolCall pendingOp =
        new PendingToolCall("op-123", "tc-123", "test-tool", "{}");

    ToolObservationContext observationContext =
        new ToolObservationContext("proc-test", 1L, "op-123", event -> {});

    // When
    reconstructor.executeApprovedBatch(
        List.of(pendingOp), List.of(), List.of(), new ArrayList<>(), observationContext, List.of());

    // Then - intent recorded BEFORE delegate called
    assertThat(executionOrder)
        .as("Intent must be recorded before delegate invocation")
        .containsExactly("INTENT_RECORDED:op-123", "DELEGATE_CALLED:test-tool");
  }

  // Test: Tool failure after successful intent recording

  @Test
  void toolFailureAfterIntentRecording_shouldEmitToolFailedNotGateFailure() {
    // Given - successful store
    InMemoryInvocationStateStore store = new InMemoryInvocationStateStore();

    // Given - failing delegate
    ToolCallback failingTool =
        TestTools.createFailingTool("test-tool", new RuntimeException("Tool execution failed"));

    // Given - event recorder
    List<String> events = new ArrayList<>();
    ToolObservationContext observationContext =
        new ToolObservationContext(
            "proc-test",
            1L,
            "op-123",
            event -> events.add(event.eventType().name() + ":" + event.processId()));

    // Given - reconstructor
    ProtocolReconstructor reconstructor =
        new ProtocolReconstructor(List.of(failingTool), store);

    // Given - pending operation
    PendingToolCall pendingOp =
        new PendingToolCall("op-123", "tc-123", "test-tool", "{}");

    // When - execute (delegate throws after intent succeeds)
    assertThatThrownBy(
            () ->
                reconstructor.executeApprovedBatch(
                    List.of(pendingOp), List.of(), List.of(), new ArrayList<>(), observationContext, List.of()))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("Tool execution failed");

    // Then - intent WAS recorded (before delegate threw)
    assertThat(store.hasInvocationIntent("proc-test", "op-123", "attempt-test"))
        .as("Intent should be recorded even when delegate fails")
        .isTrue();

    // Then - TOOL_FAILED event emitted (not gate failure)
    assertThat(events)
        .as("TOOL_FAILED event should be emitted for delegate exception")
        .contains("TOOL_FAILED:proc-test");
  }

  // Test: Tool success after successful intent recording

  @Test
  void toolSuccessAfterIntentRecording_shouldEmitToolExecuted() {
    // Given - successful store
    InMemoryInvocationStateStore store = new InMemoryInvocationStateStore();

    // Given - successful delegate
    ToolCallback successfulTool = TestTools.createSuccessfulTool("test-tool", "success result");

    // Given - event recorder
    List<String> events = new ArrayList<>();
    ToolObservationContext observationContext =
        new ToolObservationContext(
            "proc-test", 1L, "op-123", event -> events.add(event.eventType().name()));

    // Given - reconstructor
    ProtocolReconstructor reconstructor =
        new ProtocolReconstructor(List.of(successfulTool), store);

    // Given - pending operation
    PendingToolCall pendingOp =
        new PendingToolCall("op-123", "tc-123", "test-tool", "{}");

    // When
    reconstructor.executeApprovedBatch(
        List.of(pendingOp), List.of(), List.of(), new ArrayList<>(), observationContext, List.of());

    // Then - intent recorded
    assertThat(store.hasInvocationIntent("proc-test", "op-123", "attempt-test"))
        .as("Intent should be recorded")
        .isTrue();

    // Then - TOOL_EXECUTED event emitted
    assertThat(events)
        .as("TOOL_EXECUTED event should be emitted for successful delegate")
        .contains("TOOL_EXECUTED");

    // Then - TOOL_FAILED not emitted
    assertThat(events)
        .as("TOOL_FAILED should not be emitted for successful delegate")
        .doesNotContain("TOOL_FAILED");
  }

  // Test: Multiple operations - partial gate failure

  @Test
  void multipleOperations_firstOperationGateFails_shouldNotExecuteAny() {
    // Given - store that fails on first operation only
    AtomicInteger callCount = new AtomicInteger(0);
    InvocationStateStore partiallyFailingStore =
        new InvocationStateStore() {
          @Override
          public void recordInvocationIntent(String processId, String operationId, String attemptId) {
            int count = callCount.incrementAndGet();
            if (count == 1) {
              throw new InvocationIntentPersistenceException(
                  "First operation gate failure: " + operationId);
            }
            // Subsequent operations would succeed (but shouldn't reach here)
          }

          @Override
          public boolean hasInvocationIntent(String processId, String operationId, String attemptId) {
            // Not used in this test
            return false;
          }

          @Override
          public java.util.List<InvocationAttempt> findAttempts(String processId, String operationId) {
            return java.util.List.of();
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

    // Given - counting delegates
    AtomicInteger delegate1Calls = new AtomicInteger(0);
    AtomicInteger delegate2Calls = new AtomicInteger(0);
    ToolCallback tool1 = TestTools.createCountingTool("tool-1", delegate1Calls);
    ToolCallback tool2 = TestTools.createCountingTool("tool-2", delegate2Calls);

    // Given - reconstructor
    ProtocolReconstructor reconstructor =
        new ProtocolReconstructor(List.of(tool1, tool2), partiallyFailingStore);

    // Given - two pending operations
    List<PendingToolCall> pendingBatch =
        List.of(
            new PendingToolCall("op-1", "tc-1", "tool-1", "{}"),
            new PendingToolCall("op-2", "tc-2", "tool-2", "{}"));

    ToolObservationContext observationContext =
        new ToolObservationContext("proc-test", 1L, "base-op", event -> {});

    // When - execute (first operation gate fails)
    assertThatThrownBy(
            () ->
                reconstructor.executeApprovedBatch(
                    pendingBatch, List.of(), List.of(), new ArrayList<>(), observationContext, List.of()))
        .isInstanceOf(InvocationIntentPersistenceException.class)
        .hasMessageContaining("First operation gate failure");

    // Then - NEITHER delegate was called (gate failure stops batch)
    assertThat(delegate1Calls.get())
        .as("First delegate should not be called (gate failed)")
        .isEqualTo(0);
    assertThat(delegate2Calls.get())
        .as("Second delegate should not be called (batch stopped)")
        .isEqualTo(0);
  }
}
