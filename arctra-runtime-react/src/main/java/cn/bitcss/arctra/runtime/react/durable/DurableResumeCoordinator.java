package cn.bitcss.arctra.runtime.react.durable;

import cn.bitcss.arctra.agent.AgentDefinition;
import cn.bitcss.arctra.agent.AgentExecutionContext;
import cn.bitcss.arctra.agent.AgentResult;
import cn.bitcss.arctra.checkpoint.CheckpointStore;
import cn.bitcss.arctra.checkpoint.ContinuationDisposition;
import cn.bitcss.arctra.checkpoint.PendingToolCall;
import cn.bitcss.arctra.checkpoint.SuspensionCheckpoint;
import cn.bitcss.arctra.evidence.Evidence;
import cn.bitcss.arctra.execution.EventType;
import cn.bitcss.arctra.execution.ExecutionEvent;
import cn.bitcss.arctra.execution.ExecutionEventListener;
import cn.bitcss.arctra.procedure.ProcedureExecutionState;
import cn.bitcss.arctra.process.AgentProcess;
import cn.bitcss.arctra.process.ContinuationSignal;
import cn.bitcss.arctra.recovery.RecoveryUncertaintyException;
import cn.bitcss.arctra.runtime.ProcessFactory;
import cn.bitcss.arctra.runtime.ResumePreparationException;
import cn.bitcss.arctra.runtime.RuntimeBinding;
import cn.bitcss.arctra.runtime.RuntimeBindingResolver;
import cn.bitcss.arctra.runtime.react.protocol.ResumedExecutionHandler;
import cn.bitcss.arctra.runtime.react.protocol.ResumedExecutionOutcome;
import cn.bitcss.arctra.runtime.react.tool.ToolObservationContext;
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
public final class DurableResumeCoordinator {

  private final CheckpointStore checkpointStore;
  private final RuntimeBindingResolver bindingResolver;
  private final ExecutionEventListener eventListener;
  private final ResumedExecutionHandler resumedExecutionHandler;
  private final cn.bitcss.arctra.runtime.DurableExecutionEngine durableEngine;
  // M6-T4C Phase 1: Recovery classifier (package-private internal)
  private final InvocationRecoveryClassifier recoveryClassifier;
  // M6-T5: Recovery resolution capability (lazy-initialized)
  private cn.bitcss.arctra.runtime.RecoveryResolution recoveryResolution;

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
  public DurableResumeCoordinator(
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
   * Access recovery resolution capability (M6-T5).
   *
   * <p>Lazy-initializes the RecoveryResolution instance on first access.
   *
   * @return recovery resolution API
   * @since M6-T5
   */
  public cn.bitcss.arctra.runtime.RecoveryResolution recovery() {
    if (recoveryResolution == null) {
      InvocationStateStore invocationStateStore = recoveryClassifier.getInvocationStateStore();
      recoveryResolution =
          new DefaultRecoveryResolution(checkpointStore, invocationStateStore, eventListener);
    }
    return recoveryResolution;
  }

  /**
   * Resume durable process execution with automatic recovery mode selection.
   *
   * <p><strong>M6-T4F: Automatic Recovery Mode Selection (Inside CHECK A).</strong>
   *
   * <p>Performs full durable resume orchestration with automatic restart detection:
   *
   * <ol>
   *   <li>CHECK A: load and validate checkpoint (authoritative)
   *   <li>Compare checkpoint.executionEpoch vs current epoch (mode selection)
   *   <li>Route to normal resume or recovery-classified resume
   *   <li>Resolve runtime binding using checkpoint.runtimeBindingKey()
   *   <li>Validate continuation signal
   *   <li>Emit APPROVAL_GRANTED / APPROVAL_REJECTED
   *   <li>Emit RESUMED
   *   <li>Delegate to resumed execution handler
   *   <li>Handle provider outcome (CHECK B)
   * </ol>
   *
   * <h2>Mode Selection Semantics</h2>
   *
   * <ul>
   *   <li><strong>Same incarnation:</strong> checkpoint.executionEpoch = currentExecutionEpoch →
   *       normal resume (no invocation-state recovery reads)
   *   <li><strong>Cross-incarnation:</strong> checkpoint.executionEpoch ≠ currentExecutionEpoch →
   *       recovery classification (preflight intent check, fail-closed on uncertainty)
   * </ul>
   *
   * <h2>TOCTOU Prevention</h2>
   *
   * <p>Mode selection uses the SAME checkpoint loaded by CHECK A. No pre-read. No race window.
   *
   * @param processId process ID to resume
   * @param checkpointVersion expected checkpoint version
   * @param signal continuation signal (approved/rejected)
   * @param currentExecutionEpoch current execution incarnation for restart detection
   * @return agent result (completed or suspended)
   * @throws cn.bitcss.arctra.checkpoint.CheckpointNotFoundException if checkpoint missing
   * @throws cn.bitcss.arctra.checkpoint.StaleCheckpointException if version mismatch
   * @throws ResumePreparationException if binding resolution fails
   * @throws RecoveryUncertaintyException if cross-incarnation recovery encounters uncertain state
   * @throws cn.bitcss.arctra.checkpoint.CheckpointTransitionConflictException if CHECK B CAS fails
   * @since M6-T4F
   */
  public AgentResult resume(
      String processId,
      long checkpointVersion,
      ContinuationSignal signal,
      String currentExecutionEpoch) {

    // CHECK A: Load and validate checkpoint (authoritative)
    SuspensionCheckpoint checkpoint = loadAndValidateCheckpoint(processId, checkpointVersion);

    // M6-T4F: Mode selection using THIS validated checkpoint (TOCTOU-safe)
    if (requiresRecoveryMode(checkpoint, currentExecutionEpoch)) {
      // Cross-incarnation → recovery classification pathway
      return resumeWithRecoveryInternal(checkpoint, signal);
    } else {
      // Same-incarnation → normal resume pathway
      return resumeNormalInternal(checkpoint, signal);
    }
  }

  /**
   * Determine whether recovery mode is required based on execution incarnation.
   *
   * <p><strong>M6-T4F: Restart detection authority.</strong>
   *
   * <p>Compares checkpoint's executionEpoch against current incarnation to detect
   * cross-incarnation resume requiring recovery classification.
   *
   * <h2>v1.0 Checkpoint Handling</h2>
   *
   * <p>Checkpoints without executionEpoch (pre-T4F) cannot be automatically classified. Throws
   * {@link ResumePreparationException} requiring explicit upgrade or checkpoint completion.
   *
   * @param checkpoint validated CHECK A checkpoint
   * @param currentEpoch current execution incarnation
   * @return true if cross-incarnation (recovery mode), false if same-incarnation (normal mode)
   * @throws ResumePreparationException if checkpoint lacks executionEpoch (v1.0)
   */
  private boolean requiresRecoveryMode(SuspensionCheckpoint checkpoint, String currentEpoch) {
    String checkpointEpoch = checkpoint.executionEpoch();

    if (checkpointEpoch == null) {
      // v1.0 checkpoint without executionEpoch - cannot auto-detect restart
      throw new ResumePreparationException(
          "Cannot auto-detect restart for pre-T4F checkpoint (schema "
              + checkpoint.schemaVersion()
              + ", missing executionEpoch). "
              + "Complete or abandon checkpoint before upgrading to T4F, "
              + "or use explicit recovery activation. "
              + "Process: "
              + checkpoint.processId()
              + ", version: "
              + checkpoint.checkpointVersion());
    }

    // Compare epochs: different = cross-incarnation = recovery mode
    return !checkpointEpoch.equals(currentEpoch);
  }

  /**
   * Normal resume pathway (same-incarnation).
   *
   * <p><strong>M6-T4F: Zero recovery-classification reads.</strong>
   *
   * <p>Used when checkpoint was created by same execution incarnation. Executes with normal
   * at-least-once semantics without invocation-state recovery reads.
   *
   * @param checkpoint validated CHECK A checkpoint
   * @param signal continuation signal
   * @return agent result
   */
  private AgentResult resumeNormalInternal(SuspensionCheckpoint checkpoint, ContinuationSignal signal) {
    // Resolve RuntimeBinding using checkpoint.runtimeBindingKey()
    RuntimeBinding binding = resolveBinding(checkpoint.processId(), checkpoint);

    // Validate signal and emit approval event
    validateAndEmitApproval(checkpoint.processId(), checkpoint.checkpointVersion(), signal);

    // M6-T2A: RESUMED domain fact becomes TRUE
    emitEvent(
        checkpoint.processId(),
        EventType.RESUMED,
        checkpoint.checkpointVersion(),
        String.format(
            "{\"checkpointVersion\": %d, \"runtimeBindingKey\": \"%s\"}",
            checkpoint.checkpointVersion(), checkpoint.runtimeBindingKey()));

    // Delegate to resumed execution handler
    List<Evidence> historicalEvidences = checkpoint.accumulatedEvidences();
    ToolObservationContext baseObservationContext =
        new ToolObservationContext(
            checkpoint.processId(),
            checkpoint.checkpointVersion(),
            "resume-base",
            eventListener);

    ResumedExecutionOutcome outcome =
        resumedExecutionHandler.executeResume(
            checkpoint.pendingBatch(),
            binding,
            historicalEvidences,
            signal,
            baseObservationContext,
            null); // Same-incarnation: no recovery classification needed

    // Handle provider outcome with CHECK B
    return handleResumedExecutionOutcome(
        outcome, checkpoint, binding.definition(), binding.context());
  }

  /**
   * Recovery-classified resume pathway (cross-incarnation).
   *
   * <p><strong>M6-T4F: Preflight invocation-state classification.</strong>
   *
   * <p>Used when checkpoint was created by different execution incarnation (restart detected).
   * Performs preflight recovery classification before any physical tool invocation.
   *
   * <h2>Phase 1 Fail-Closed Policy</h2>
   *
   * <p>If any operation has uncertain state (MAY_HAVE_INVOKED):
   *
   * <ul>
   *   <li>Throw {@link RecoveryUncertaintyException}
   *   <li>Checkpoint preserved (CHECK B not reached)
   *   <li>No physical tool invocation
   *   <li>Manual investigation required
   * </ul>
   *
   * @param checkpoint validated CHECK A checkpoint
   * @param signal continuation signal
   * @return agent result
   * @throws RecoveryUncertaintyException if any operation MAY_HAVE_INVOKED
   */
  private AgentResult resumeWithRecoveryInternal(
      SuspensionCheckpoint checkpoint, ContinuationSignal signal) {

    // Resolve binding first (needed for execution)
    RuntimeBinding binding = resolveBinding(checkpoint.processId(), checkpoint);

    // RECOVERY CLASSIFICATION GATE (M6-T5: Multi-attempt aggregation)
    // Only for APPROVE - REJECT does not invoke tools physically
    List<RecoveryClassificationResult> classifications = null;
    if (signal instanceof ContinuationSignal.ApprovalSignal approvalSignal
        && approvalSignal.approved()) {
      classifications = classifyApprovedBatchOrFailClosed(checkpoint.processId(), checkpoint.pendingBatch());
    }

    // After classification gate passed, continue with normal orchestration
    validateAndEmitApproval(checkpoint.processId(), checkpoint.checkpointVersion(), signal);

    // M6-T2A: RESUMED domain fact becomes TRUE
    emitEvent(
        checkpoint.processId(),
        EventType.RESUMED,
        checkpoint.checkpointVersion(),
        String.format(
            "{\"checkpointVersion\": %d, \"runtimeBindingKey\": \"%s\", \"recoveryMode\": true}",
            checkpoint.checkpointVersion(), checkpoint.runtimeBindingKey()));

    // Delegate to resumed execution handler (M6-T5: pass classifications)
    List<Evidence> historicalEvidences = checkpoint.accumulatedEvidences();
    ToolObservationContext baseObservationContext =
        new ToolObservationContext(
            checkpoint.processId(),
            checkpoint.checkpointVersion(),
            "resume-recovery",
            eventListener);

    ResumedExecutionOutcome outcome =
        resumedExecutionHandler.executeResume(
            checkpoint.pendingBatch(),
            binding,
            historicalEvidences,
            signal,
            baseObservationContext,
            classifications);

    // Handle provider outcome with CHECK B (same as normal path)
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
  /**
   * Classify entire approved batch and fail closed on uncertainty.
   *
   * <p><strong>M6-T5: Whole-batch preflight with multi-attempt aggregation.</strong>
   *
   * <p>Performs recovery classification for ALL operations in the pending batch before allowing any
   * physical invocation. If ANY operation has uncertain state (unresolved attempts), entire recovery
   * fails closed.
   *
   * @param processId process ID
   * @param pendingBatch approved operations to classify
   * @return list of classification results (only if all safe)
   * @throws RecoveryUncertaintyException if any operation has unresolved attempts
   * @since M6-T4C Phase 1
   * @since M6-T5 Multi-attempt aggregation
   */
  private List<RecoveryClassificationResult> classifyApprovedBatchOrFailClosed(
      String processId, List<PendingToolCall> pendingBatch) {

    List<RecoveryClassificationResult> results = new java.util.ArrayList<>();

    // Whole-batch preflight: classify ALL operations before execution
    for (PendingToolCall operation : pendingBatch) {
      RecoveryClassificationResult classification =
          recoveryClassifier.classify(processId, operation);

      if (classification instanceof MayHaveInvoked mayHaveInvoked) {
        // Uncertain operation detected - fail closed BEFORE any execution
        // Emit RECOVERY_UNCERTAIN event
        emitRecoveryUncertainEvent(
            processId, operation.operationId(), mayHaveInvoked.unresolvedAttemptIds());

        // Throw exception with all unresolved attempts
        throw new RecoveryUncertaintyException(
            String.format(
                "Recovery cannot proceed: operation %s (tool: %s) has %d unresolved physical attempt(s). "
                    + "Invocation intent exists but external outcome unknown. "
                    + "Operator resolution required via runtime.recovery() API. "
                    + "Process: %s, checkpoint preserved.",
                operation.operationId(),
                operation.toolName(),
                mayHaveInvoked.unresolvedAttemptIds().size(),
                processId),
            processId,
            operation.operationId(),
            mayHaveInvoked.unresolvedAttemptIds());
      }

      results.add(classification);
    }

    // All operations safe - return classifications for execution planning
    return results;
  }

  /**
   * Emit RECOVERY_UNCERTAIN event.
   *
   * @param processId process ID
   * @param operationId operation ID
   * @param unresolvedAttemptIds list of unresolved attempt IDs
   */
  private void emitRecoveryUncertainEvent(
      String processId, String operationId, List<String> unresolvedAttemptIds) {
    try {
      String attemptIdsJson =
          unresolvedAttemptIds.stream()
              .map(id -> "\"" + id + "\"")
              .collect(java.util.stream.Collectors.joining(","));

      String payload =
          String.format(
              "{\"operationId\":\"%s\",\"attemptIds\":[%s]}", operationId, attemptIdsJson);

      eventListener.onEvent(
          new ExecutionEvent(processId, EventType.RECOVERY_UNCERTAIN, null, payload));
    } catch (Exception e) {
      // Event projection failure does not affect recovery classification
    }
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
            ContinuationDisposition.WAITING_FOR_SIGNAL, // M6-T6.4: re-suspended awaiting approval
            suspended.pendingBatch(),
            suspended.evidences(),
            ExecutionIncarnation.current(), // M6-T4F: rollover to current
            (ProcedureExecutionState) null); // M8-Integration: procedureState

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
