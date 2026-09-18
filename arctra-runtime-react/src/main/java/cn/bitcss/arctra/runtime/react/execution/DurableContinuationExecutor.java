package cn.bitcss.arctra.runtime.react.execution;

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
import cn.bitcss.arctra.recovery.RecoveryUncertaintyException;
import cn.bitcss.arctra.runtime.DurableExecutionEngine;
import cn.bitcss.arctra.runtime.react.durable.ExecutionIncarnation;
import cn.bitcss.arctra.runtime.react.durable.InvocationRecoveryClassifier;
import cn.bitcss.arctra.runtime.react.durable.InvocationStateStore;
import cn.bitcss.arctra.runtime.react.durable.MayHaveInvoked;
import cn.bitcss.arctra.runtime.react.durable.RecoveryClassificationResult;
import cn.bitcss.arctra.runtime.react.governance.GovernanceToolCallingAdvisor;
import cn.bitcss.arctra.runtime.react.protocol.ProtocolReconstructor;
import cn.bitcss.arctra.runtime.react.tool.ToolObservationContext;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.tool.ToolCallback;

/**
 * Shared durable continuation execution core.
 *
 * <p><strong>M6-T6.4 Execution Flow Decomposition</strong>
 *
 * <p>This is the convergence point for all durable continuation execution, used by:
 *
 * <ul>
 *   <li>DURABLE ALLOW after checkpoint materialization
 *   <li>DURABLE RESUME after CHECK A and signal validation
 * </ul>
 *
 * <p><strong>Execution sequence:</strong>
 *
 * <ol>
 *   <li>Determine if same-incarnation or cross-incarnation
 *   <li>If cross-incarnation: classify operations via InvocationRecoveryClassifier
 *   <li>Execute tools via ProtocolReconstructor with T5 integration
 *   <li>Continue model with tool responses (via ModelContinuationExecutor)
 *   <li>CHECK B: Delete checkpoint after completion
 *   <li>Best-effort InvocationStateStore cleanup
 * </ol>
 *
 * <p><strong>Responsibility:</strong>
 *
 * <ul>
 *   <li>Orchestrate execution-after-preparation
 *   <li>T5 recovery classification for cross-incarnation
 *   <li>Tool execution via ProtocolReconstructor
 *   <li>Model continuation delegation
 *   <li>CHECK B checkpoint deletion
 *   <li>InvocationStateStore cleanup
 * </ul>
 *
 * <p><strong>Does NOT own:</strong>
 *
 * <ul>
 *   <li>Checkpoint creation (owned by DurableExecutionHandler)
 *   <li>CHECK A loading (owned by DurableResumeCoordinator)
 *   <li>Signal validation (owned by DurableResumeCoordinator)
 *   <li>Governance policy (owned by GovernanceToolCallingAdvisor)
 * </ul>
 *
 * @author lov3r
 * @since M6-T6.4
 */
public class DurableContinuationExecutor {

  private final CheckpointStore checkpointStore;
  private final InvocationStateStore invocationStateStore;
  private final List<ToolCallback> tools;
  private final ChatMemory chatMemory;
  private final ExecutionEventListener executionEventSink;
  private final ModelContinuationExecutor modelContinuationExecutor;
  private final DurableExecutionEngine engine; // For ProcessFactory.createDurableSuspended

  public DurableContinuationExecutor(
      CheckpointStore checkpointStore,
      InvocationStateStore invocationStateStore,
      List<ToolCallback> tools,
      ChatMemory chatMemory,
      ExecutionEventListener executionEventSink,
      ModelContinuationExecutor modelContinuationExecutor,
      DurableExecutionEngine engine) {
    this.checkpointStore = checkpointStore;
    this.invocationStateStore = invocationStateStore;
    this.tools = tools;
    this.chatMemory = chatMemory;
    this.executionEventSink = executionEventSink;
    this.modelContinuationExecutor = modelContinuationExecutor;
    this.engine = engine;
  }

  /**
   * Execute from materialized checkpoint (DURABLE ALLOW path or RESUME path).
   *
   * <p><strong>M6-T6.4 Phase 7: Full T5 Integration with Crash Recovery.</strong>
   *
   * <p>Shared execution core for both:
   *
   * <ul>
   *   <li>Same-incarnation auto-continue (ALLOW path)
   *   <li>Cross-incarnation recovery (RESUME path)
   * </ul>
   *
   * @param checkpoint validated checkpoint with RUNNABLE or approved state
   * @param suspensionState original suspension state (may be null for resume path)
   * @param evidences accumulated evidences
   * @param definition agent definition
   * @param context execution context
   * @return completed agent result
   * @throws RecoveryUncertaintyException if cross-incarnation recovery encounters uncertainty
   */
  public AgentResult executeFromCheckpoint(
      SuspensionCheckpoint checkpoint,
      GovernanceToolCallingAdvisor.SuspensionState suspensionState,
      List<Evidence> evidences,
      AgentDefinition definition,
      AgentExecutionContext context) {

    String processId = checkpoint.processId();
    List<PendingToolCall> pendingBatch = checkpoint.pendingBatch();
    long checkpointVersion = checkpoint.checkpointVersion();

    // Phase 7: Determine if this is same-incarnation or cross-incarnation execution
    boolean isCrossIncarnation =
        !checkpoint.executionEpoch().equals(ExecutionIncarnation.current());

    List<RecoveryClassificationResult> classifications = null;
    if (isCrossIncarnation) {
      // Cross-incarnation recovery: classify operations
      InvocationRecoveryClassifier classifier =
          new InvocationRecoveryClassifier(invocationStateStore);
      classifications = new ArrayList<>();

      for (PendingToolCall pending : pendingBatch) {
        RecoveryClassificationResult classification = classifier.classify(processId, pending);
        classifications.add(classification);

        // Check for unresolved uncertain attempts
        if (classification
            instanceof MayHaveInvoked(String operationId, List<String> unresolvedAttemptIds)) {
          // Unresolved uncertain operation - cannot proceed
          emitEvent(
              processId,
              EventType.FAILED,
              checkpointVersion,
              String.format(
                  "{\"reason\": \"recovery_uncertainty\", \"operationId\": \"%s\"}", operationId));
          throw new RecoveryUncertaintyException(
              "Recovery uncertainty: operation " + operationId + " has unresolved attempts",
              processId,
              operationId,
              unresolvedAttemptIds);
        }
      }
    }

    // Phase 7: Execute tools via ProtocolReconstructor with T5 integration
    String sessionId = context.sessionId();
    List<Message> conversationHistory =
        (sessionId != null) ? chatMemory.get(sessionId) : List.of();

    // Create observation context for tool execution
    ToolObservationContext observationContext =
        new ToolObservationContext(processId, checkpointVersion, null, executionEventSink);

    // Create ProtocolReconstructor with T5 integration
    ProtocolReconstructor reconstructor = new ProtocolReconstructor(tools, invocationStateStore);

    // Wrap tools with evidence capturing
    List<Evidence> newEvidences = Collections.synchronizedList(new ArrayList<>());

    // Execute approved batch (with classifications for cross-incarnation recovery)
    List<Message> continuationMessages =
        reconstructor.executeApprovedBatch(
            pendingBatch,
            conversationHistory,
            evidences,
            newEvidences,
            observationContext,
            classifications);

    // Merge evidences before continuation
    List<Evidence> mergedEvidences = new ArrayList<>(evidences);
    mergedEvidences.addAll(newEvidences);

    // Continue model with tool responses (via ModelContinuationExecutor)
    AgentResult result =
        modelContinuationExecutor.continueWithMessages(
            continuationMessages, mergedEvidences, definition, context);

    // CHECK B: Delete checkpoint (Phase 9)
    boolean deleted = checkpointStore.deleteIfVersion(processId, checkpointVersion);
    if (!deleted) {
      emitEvent(
          processId,
          EventType.CHECKPOINT_CONFLICT,
          checkpointVersion,
          String.format("{\"operation\": \"DELETE\", \"reason\": \"version mismatch\"}"));
      throw new cn.bitcss.arctra.checkpoint.CheckpointTransitionConflictException(
          "CHECK B delete failed for " + processId + " at version " + checkpointVersion);
    }

    // Phase 9: Best-effort InvocationStateStore cleanup
    // This is best-effort: checkpoint deletion succeeded (the critical fact)
    // If cleanup fails, stale state remains but does not affect correctness
    try {
      for (PendingToolCall pending : pendingBatch) {
        invocationStateStore.deleteInvocationState(processId, pending.operationId());
      }
    } catch (Exception e) {
      // Log but don't fail - checkpoint is the authority
      System.err.println(
          "Warning: InvocationStateStore cleanup failed for " + processId + ": " + e.getMessage());
    }

    // Emit COMPLETED event
    emitEvent(
        processId,
        EventType.COMPLETED,
        checkpointVersion,
        String.format("{\"checkpointVersion\": %d}", checkpointVersion));

    // Return completed result
    return result;
  }

  /**
   * Get the engine instance (for ProcessFactory.createDurableSuspended).
   *
   * @return durable execution engine
   */
  public DurableExecutionEngine getEngine() {
    return engine;
  }

  private void emitEvent(
      String processId, EventType eventType, Long checkpointVersion, String payload) {
    executionEventSink.onEvent(
        new ExecutionEvent(processId, eventType, checkpointVersion, payload));
  }
}
