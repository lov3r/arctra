package cn.bitcss.arctra.runtime.react;

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
import cn.bitcss.arctra.process.AgentProcess;
import cn.bitcss.arctra.process.ContinuationSignal;
import cn.bitcss.arctra.runtime.RuntimeBinding;
import cn.bitcss.arctra.runtime.RuntimeBindingResolver;
import java.util.List;
import java.util.Objects;

/**
 * Durable resume coordinator.
 *
 * <p>Owns durable resume orchestration semantics including CHECK A, runtime binding resolution,
 * approval/rejection lifecycle, resumed execution delegation, CHECK B transitions, and process
 * materialization.
 *
 * <p><strong>Responsibilities</strong>:
 *
 * <ul>
 *   <li>CHECK A: checkpoint load and version validation
 *   <li>RuntimeBinding resolution using checkpoint.runtimeBindingKey()
 *   <li>ContinuationSignal validation
 *   <li>APPROVAL_GRANTED / APPROVAL_REJECTED event emission
 *   <li>RESUMED event emission
 *   <li>Resumed execution delegation to provider handler
 *   <li>CHECK B completion: deleteIfVersion
 *   <li>CHECK B re-suspension: replaceIfVersion
 *   <li>CHECKPOINT_CONFLICT event emission on CAS failure
 *   <li>COMPLETED event emission after successful completion
 *   <li>APPROVAL_REQUIRED event emission for new governance suspension
 *   <li>SUSPENDED event emission after successful re-suspension
 *   <li>Next checkpoint construction preserving identity
 *   <li>AgentProcess materialization after successful transitions
 * </ul>
 *
 * <p><strong>NOT responsible for</strong>:
 *
 * <ul>
 *   <li>Spring AI protocol reconstruction (provider handler owns this)
 *   <li>Tool execution mechanics (provider handler owns this)
 *   <li>ChatClient / ChatModel interaction (provider handler owns this)
 *   <li>ChatMemory management (provider handler post-commit action)
 *   <li>Initial execution
 *   <li>Initial suspension
 * </ul>
 *
 * <p>This coordinator is framework-neutral and has zero Spring AI dependencies.
 *
 * @author lov3r
 */
final class DurableResumeCoordinator {

  private final CheckpointStore checkpointStore;
  private final RuntimeBindingResolver bindingResolver;
  private final ExecutionEventListener eventListener;
  private final ResumedExecutionHandler resumedExecutionHandler;
  private final cn.bitcss.arctra.runtime.DurableExecutionEngine durableEngine;
  // M6-T4C Phase 1: Recovery classifier (package-private internal)
  private final InvocationRecoveryClassifier recoveryClassifier;

  /**
   * Construct durable resume coordinator.
   *
   * @param checkpointStore checkpoint store for CHECK A and CHECK B
   * @param bindingResolver runtime binding resolver
   * @param eventListener execution event listener for lifecycle events
   * @param resumedExecutionHandler resumed execution handler for provider mechanics
   * @param durableEngine durable execution engine for process creation
   * @param recoveryClassifier recovery classifier for M6-T4C explicit recovery pathway
   */
  DurableResumeCoordinator(
      CheckpointStore checkpointStore,
      RuntimeBindingResolver bindingResolver,
      ExecutionEventListener eventListener,
      ResumedExecutionHandler resumedExecutionHandler,
      cn.bitcss.arctra.runtime.DurableExecutionEngine durableEngine,
      InvocationRecoveryClassifier recoveryClassifier) {

    this.checkpointStore =
        Objects.requireNonNull(checkpointStore, "checkpointStore cannot be null");
    this.bindingResolver =
        Objects.requireNonNull(bindingResolver, "bindingResolver cannot be null");
    this.eventListener = Objects.requireNonNull(eventListener, "eventListener cannot be null");
    this.resumedExecutionHandler =
        Objects.requireNonNull(resumedExecutionHandler, "resumedExecutionHandler cannot be null");
    this.durableEngine =
        Objects.requireNonNull(durableEngine, "durableEngine cannot be null");
    this.recoveryClassifier =
        Objects.requireNonNull(recoveryClassifier, "recoveryClassifier cannot be null");
  }

  /**
   * Resume durable process execution.
   *
   * <p>Performs full durable resume orchestration:
   *
   * <ol>
   *   <li>CHECK A: load and validate checkpoint
   *   <li>Resolve runtime binding using checkpoint.runtimeBindingKey()
   *   <li>Validate continuation signal
   *   <li>Emit APPROVAL_GRANTED / APPROVAL_REJECTED
   *   <li>Emit RESUMED
   *   <li>Delegate to resumed execution handler
   *   <li>Handle provider outcome (CHECK B)
   * </ol>
   *
   * @param processId process ID to resume
   * @param checkpointVersion expected checkpoint version
   * @param signal continuation signal (approved/rejected)
   * @return agent result (completed or suspended)
   * @throws cn.bitcss.arctra.checkpoint.CheckpointNotFoundException if checkpoint missing
   * @throws cn.bitcss.arctra.checkpoint.StaleCheckpointException if version mismatch
   * @throws ResumePreparationException if binding resolution fails
   * @throws cn.bitcss.arctra.checkpoint.CheckpointTransitionConflictException if CHECK B CAS fails
   */
  AgentResult resume(String processId, long checkpointVersion, ContinuationSignal signal) {

    // CHECK A: Load and validate checkpoint
    SuspensionCheckpoint checkpoint = loadAndValidateCheckpoint(processId, checkpointVersion);

    // Resolve RuntimeBinding using checkpoint.runtimeBindingKey()
    RuntimeBinding binding = resolveBinding(processId, checkpoint);

    // Validate signal and emit approval event
    validateAndEmitApproval(processId, checkpoint.checkpointVersion(), signal);

    // M6-T2A: RESUMED domain fact becomes TRUE
    emitEvent(
        processId,
        EventType.RESUMED,
        checkpoint.checkpointVersion(),
        String.format(
            "{\"checkpointVersion\": %d, \"runtimeBindingKey\": \"%s\"}",
            checkpoint.checkpointVersion(), checkpoint.runtimeBindingKey()));

    // Delegate to resumed execution handler
    List<Evidence> historicalEvidences = checkpoint.accumulatedEvidences();
    // M6-T3B: Base observation context (per-operation contexts created during execution)
    ToolObservationContext baseObservationContext =
        new ToolObservationContext(
            processId,
            checkpoint.checkpointVersion(),
            "resume-base", // Placeholder - will be replaced per-operation
            eventListener);

    ResumedExecutionOutcome outcome =
        resumedExecutionHandler.executeResume(
            checkpoint.pendingBatch(),
            binding,
            historicalEvidences,
            signal,
            baseObservationContext);

    // Handle provider outcome with CHECK B
    return handleResumedExecutionOutcome(
        outcome, checkpoint, binding.definition(), binding.context());
  }

  /**
   * Resume durable process with explicit recovery classification.
   *
   * <p><strong>M6-T4C Phase 1: Explicit recovery pathway (package-private internal).</strong>
   *
   * <p>This is an INTERNAL recovery pathway that adds preflight invocation-state classification
   * before execution. It performs the same orchestration as {@link #resume(String, long,
   * ContinuationSignal)} but with recovery classification gate.
   *
   * <h2>Recovery Classification Gate</h2>
   *
   * <p>After CHECK A, classifies each pending approved operation:
   *
   * <ul>
   *   <li>{@link InvocationRecoveryClassification#DEFINITELY_NOT_DISPATCHED}: Safe to execute (no
   *       intent)
   *   <li>{@link InvocationRecoveryClassification#MAY_HAVE_INVOKED}: Uncertain (intent exists) →
   *       fail closed
   * </ul>
   *
   * <p><strong>Batch preflight:</strong> ALL operations classified BEFORE any physical execution.
   * If ANY operation has uncertain state, NO operations execute.
   *
   * <h2>Phase 1 Fail-Closed Policy</h2>
   *
   * <p>If any operation classified {@code MAY_HAVE_INVOKED}:
   *
   * <ul>
   *   <li>Throw {@link RecoveryUncertaintyException}
   *   <li>Checkpoint preserved (CHECK B not reached)
   *   <li>No physical tool invocation
   *   <li>Manual investigation required
   * </ul>
   *
   * <p>This is NOT retry policy. It is safety boundary while recovery policy does not exist.
   *
   * <h2>Normal Resume Unchanged</h2>
   *
   * <p>This pathway does NOT affect {@link #resume(String, long, ContinuationSignal)}. Normal
   * concurrent resume remains at-least-once without invocation-state reads.
   *
   * <h2>Activation</h2>
   *
   * <p>Phase 1: Manual/explicit only (test or operator).
   *
   * <p>Phase 2 (future): Automatic restart detection when persistent stores exist.
   *
   * <h2>REJECT Behavior</h2>
   *
   * <p>Rejection synthesizes responses without physical tool invocation, so recovery classification
   * is NOT needed for REJECT signals. Classification gate only activated for APPROVE.
   *
   * @param processId process ID to resume
   * @param checkpointVersion expected checkpoint version
   * @param signal continuation signal (approved/rejected)
   * @return agent result (completed or suspended)
   * @throws cn.bitcss.arctra.checkpoint.CheckpointNotFoundException if checkpoint missing
   * @throws cn.bitcss.arctra.checkpoint.StaleCheckpointException if version mismatch
   * @throws ResumePreparationException if binding resolution fails
   * @throws RecoveryUncertaintyException if any operation has MAY_HAVE_INVOKED status
   * @throws cn.bitcss.arctra.checkpoint.CheckpointTransitionConflictException if CHECK B conflicts
   * @since M6-T4C Phase 1
   */
  AgentResult resumeWithRecoveryClassification(
      String processId, long checkpointVersion, ContinuationSignal signal) {

    // CHECK A: Load and validate checkpoint (reuse existing authority)
    SuspensionCheckpoint checkpoint = loadAndValidateCheckpoint(processId, checkpointVersion);

    // Resolve runtime binding (reuse existing logic)
    RuntimeBinding binding = resolveBinding(processId, checkpoint);

    // RECOVERY CLASSIFICATION GATE (M6-T4C Phase 1)
    // Only for APPROVE - REJECT does not invoke tools physically
    if (signal instanceof ContinuationSignal.ApprovalSignal approvalSignal && approvalSignal.approved()) {
      classifyApprovedBatchOrFailClosed(processId, checkpoint.pendingBatch());
    }

    // Validate and emit approval event (reuse existing logic)
    validateAndEmitApproval(processId, checkpoint.checkpointVersion(), signal);

    // M6-T2A: RESUMED domain fact becomes TRUE
    emitEvent(
        processId,
        EventType.RESUMED,
        checkpoint.checkpointVersion(),
        String.format(
            "{\"checkpointVersion\": %d, \"runtimeBindingKey\": \"%s\"}",
            checkpoint.checkpointVersion(), checkpoint.runtimeBindingKey()));

    // Delegate to resumed execution handler (reuse existing orchestration)
    List<Evidence> historicalEvidences = checkpoint.accumulatedEvidences();
    ToolObservationContext baseObservationContext =
        new ToolObservationContext(
            processId,
            checkpoint.checkpointVersion(),
            "resume-base",
            eventListener);

    ResumedExecutionOutcome outcome =
        resumedExecutionHandler.executeResume(
            checkpoint.pendingBatch(),
            binding,
            historicalEvidences,
            signal,
            baseObservationContext);

    // Handle provider outcome with CHECK B (reuse existing logic)
    return handleResumedExecutionOutcome(
        outcome, checkpoint, binding.definition(), binding.context());
  }

  /**
   * Classify approved batch or fail closed on uncertainty.
   *
   * <p><strong>M6-T4C Phase 1: Batch preflight classification gate.</strong>
   *
   * <p>Classifies ALL pending operations BEFORE any physical execution. If ANY operation has {@link
   * InvocationRecoveryClassification#MAY_HAVE_INVOKED} status, throws {@link
   * RecoveryUncertaintyException} and prevents ALL operations from executing.
   *
   * <h2>Fail-Closed Semantics</h2>
   *
   * <ul>
   *   <li>Entire batch must be safe (all DEFINITELY_NOT_DISPATCHED)
   *   <li>ANY uncertain operation → entire batch fails
   *   <li>Read failure → propagates as infrastructure exception
   *   <li>Checkpoint preserved (caller does not reach CHECK B)
   * </ul>
   *
   * @param processId process identifier
   * @param pendingBatch pending operations to classify
   * @throws RecoveryUncertaintyException if any operation MAY_HAVE_INVOKED
   * @throws RuntimeException if invocation-state read fails (fail closed)
   */
  private void classifyApprovedBatchOrFailClosed(
      String processId, List<PendingToolCall> pendingBatch) {

    // Classify entire batch before any execution
    for (PendingToolCall operation : pendingBatch) {
      InvocationRecoveryClassification classification =
          recoveryClassifier.classify(processId, operation);

      if (classification == InvocationRecoveryClassification.MAY_HAVE_INVOKED) {
        // Uncertain operation detected - fail closed
        // Checkpoint preserved, no execution, manual investigation required
        throw new RecoveryUncertaintyException(
            String.format(
                "Recovery cannot proceed: operation %s (tool: %s) may have already been invoked. "
                    + "Invocation intent exists but external outcome unknown. "
                    + "Manual investigation required to determine safe recovery action. "
                    + "Process: %s, checkpoint will be preserved for operator review.",
                operation.operationId(), operation.toolName(), processId),
            processId,
            operation.operationId());
      }
      // DEFINITELY_NOT_DISPATCHED - continue checking remaining operations
    }

    // All operations safe - proceed to execution (return normally)
  }

  /**
   * CHECK A: Load and validate checkpoint.
   *
   * @param processId process ID
   * @param requestedVersion requested checkpoint version
   * @return loaded checkpoint
   * @throws cn.bitcss.arctra.checkpoint.CheckpointNotFoundException if not found
   * @throws cn.bitcss.arctra.checkpoint.StaleCheckpointException if version mismatch
   */
  private SuspensionCheckpoint loadAndValidateCheckpoint(
      String processId, long requestedVersion) {

    SuspensionCheckpoint checkpoint =
        checkpointStore
            .load(processId)
            .orElseThrow(
                () ->
                    new cn.bitcss.arctra.checkpoint.CheckpointNotFoundException(
                        "Checkpoint not found for process " + processId));

    if (checkpoint.checkpointVersion() != requestedVersion) {
      throw new cn.bitcss.arctra.checkpoint.StaleCheckpointException(
          "Stale checkpoint version for process "
              + processId
              + ": requested "
              + requestedVersion
              + ", current "
              + checkpoint.checkpointVersion());
    }

    return checkpoint;
  }

  /**
   * Resolve runtime binding using checkpoint identity.
   *
   * <p>CRITICAL: Uses checkpoint.runtimeBindingKey(), NOT Engine's current configuration key.
   *
   * @param processId process ID
   * @param checkpoint suspension checkpoint
   * @return runtime binding
   * @throws ResumePreparationException if resolution fails
   */
  private RuntimeBinding resolveBinding(String processId, SuspensionCheckpoint checkpoint) {
    try {
      return bindingResolver.resolve(
          processId, checkpoint.runtimeBindingKey(), checkpoint.sessionId());
    } catch (Exception e) {
      throw new cn.bitcss.arctra.runtime.ResumePreparationException(
          "RuntimeBinding resolution failed for process "
              + processId
              + " with runtimeBindingKey="
              + checkpoint.runtimeBindingKey(),
          e);
    }
  }

  /**
   * Validate signal and emit approval event.
   *
   * @param processId process ID
   * @param checkpointVersion checkpoint version
   * @param signal continuation signal
   */
  private void validateAndEmitApproval(
      String processId, long checkpointVersion, ContinuationSignal signal) {

    if (signal instanceof ContinuationSignal.ApprovalSignal approval) {
      EventType approvalEvent =
          approval.approved() ? EventType.APPROVAL_GRANTED : EventType.APPROVAL_REJECTED;
      String approvalPayload =
          String.format(
              "{\"approved\": %b, \"checkpointVersion\": %d}",
              approval.approved(), checkpointVersion);

      emitEvent(processId, approvalEvent, checkpointVersion, approvalPayload);
    } else {
      throw new IllegalArgumentException(
          "Unsupported ContinuationSignal type: " + signal.getClass().getName());
    }
  }

  /**
   * Handle resumed execution outcome with CHECK B.
   *
   * @param outcome provider execution outcome
   * @param checkpoint current checkpoint
   * @param definition agent definition
   * @param context execution context
   * @return agent result
   */
  private AgentResult handleResumedExecutionOutcome(
      ResumedExecutionOutcome outcome,
      SuspensionCheckpoint checkpoint,
      AgentDefinition definition,
      AgentExecutionContext context) {

    return switch (outcome) {
      case ResumedExecutionOutcome.ModelCompleted completed ->
          handleCompletion(checkpoint, completed, context);

      case ResumedExecutionOutcome.GovernanceSuspended suspended ->
          handleReSuspension(checkpoint, suspended, context);
    };
  }

  /**
   * Handle model completion with CHECK B deleteIfVersion.
   *
   * @param checkpoint current checkpoint
   * @param completed model completed outcome
   * @param context execution context
   * @return completed agent result
   */
  private AgentResult handleCompletion(
      SuspensionCheckpoint checkpoint,
      ResumedExecutionOutcome.ModelCompleted completed,
      AgentExecutionContext context) {

    // CHECK B: Delete checkpoint (CAS)
    boolean deleted =
        checkpointStore.deleteIfVersion(
            checkpoint.processId(), checkpoint.checkpointVersion());

    if (!deleted) {
      // M6-T2A: CHECKPOINT_CONFLICT domain fact becomes TRUE
      emitEvent(
          checkpoint.processId(),
          EventType.CHECKPOINT_CONFLICT,
          checkpoint.checkpointVersion(),
          String.format(
              "{\"operation\": \"DELETE\", \"conflictType\": \"VERSION_MISMATCH\", \"checkpointVersion\": %d}",
              checkpoint.checkpointVersion()));

      throw new cn.bitcss.arctra.checkpoint.CheckpointTransitionConflictException(
          "CHECK B delete failed for process "
              + checkpoint.processId()
              + " at version "
              + checkpoint.checkpointVersion());
    }

    // M6-T2A: COMPLETED domain fact becomes TRUE
    emitEvent(
        checkpoint.processId(),
        EventType.COMPLETED,
        checkpoint.checkpointVersion(),
        String.format("{\"checkpointVersion\": %d}", checkpoint.checkpointVersion()));

    // Post-commit: Persist final assistant message to ChatMemory
    resumedExecutionHandler.persistCompletedAssistant(context, completed.content());

    return new AgentResult(completed.content(), completed.evidences());
  }

  /**
   * Handle governance re-suspension with CHECK B replaceIfVersion.
   *
   * @param oldCheckpoint current checkpoint
   * @param suspended governance suspended outcome
   * @param context execution context
   * @return suspended agent result
   */
  private AgentResult handleReSuspension(
      SuspensionCheckpoint oldCheckpoint,
      ResumedExecutionOutcome.GovernanceSuspended suspended,
      AgentExecutionContext context) {

    // M6-T2A: APPROVAL_REQUIRED domain fact becomes TRUE
    emitEvent(
        oldCheckpoint.processId(),
        EventType.APPROVAL_REQUIRED,
        oldCheckpoint.checkpointVersion(),
        String.format(
            "{\"pendingToolCallCount\": %d, \"checkpointVersion\": %d}",
            suspended.pendingBatch().size(), oldCheckpoint.checkpointVersion()));

    // Build next checkpoint (version N+1) preserving identity
    SuspensionCheckpoint nextCheckpoint =
        new SuspensionCheckpoint(
            SuspensionCheckpoint.CURRENT_SCHEMA_VERSION,
            oldCheckpoint.processId(),
            oldCheckpoint.checkpointVersion() + 1,
            oldCheckpoint.runtimeBindingKey(), // Preserve from checkpoint
            oldCheckpoint.sessionId(),
            suspended.pendingBatch(),
            suspended.evidences());

    // CHECK B: Replace checkpoint (CAS)
    boolean replaced =
        checkpointStore.replaceIfVersion(
            oldCheckpoint.processId(), oldCheckpoint.checkpointVersion(), nextCheckpoint);

    if (!replaced) {
      // M6-T2A: CHECKPOINT_CONFLICT domain fact becomes TRUE
      emitEvent(
          oldCheckpoint.processId(),
          EventType.CHECKPOINT_CONFLICT,
          oldCheckpoint.checkpointVersion(),
          String.format(
              "{\"operation\": \"REPLACE\", \"conflictType\": \"VERSION_MISMATCH\", \"oldVersion\": %d, \"newVersion\": %d}",
              oldCheckpoint.checkpointVersion(), nextCheckpoint.checkpointVersion()));

      throw new cn.bitcss.arctra.checkpoint.CheckpointTransitionConflictException(
          "CHECK B replace failed for process "
              + oldCheckpoint.processId()
              + " from version "
              + oldCheckpoint.checkpointVersion()
              + " to version "
              + nextCheckpoint.checkpointVersion());
    }

    // M6-T2A: SUSPENDED domain fact becomes TRUE
    emitEvent(
        oldCheckpoint.processId(),
        EventType.SUSPENDED,
        nextCheckpoint.checkpointVersion(),
        String.format(
            "{\"checkpointVersion\": %d, \"pendingToolCallCount\": %d}",
            nextCheckpoint.checkpointVersion(), suspended.pendingBatch().size()));

    // Create durable suspended process
    AgentProcess suspendedProcess =
        cn.bitcss.arctra.runtime.ProcessFactory.createDurableSuspended(
            nextCheckpoint.processId(), nextCheckpoint.checkpointVersion(), durableEngine);

    return new AgentResult(
        "Execution suspended - checkpoint created", suspended.evidences(), suspendedProcess);
  }

  /**
   * Emit execution event.
   *
   * @param processId process ID
   * @param eventType event type
   * @param checkpointVersion checkpoint version
   * @param payload event payload
   */
  private void emitEvent(
      String processId, EventType eventType, long checkpointVersion, String payload) {
    ExecutionEvent event =
        new ExecutionEvent(processId, eventType, checkpointVersion, payload);
    eventListener.onEvent(event);
  }
}
