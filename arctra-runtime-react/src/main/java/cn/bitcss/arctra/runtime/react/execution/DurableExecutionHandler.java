package cn.bitcss.arctra.runtime.react.execution;

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
import cn.bitcss.arctra.process.AgentProcess;
import cn.bitcss.arctra.runtime.ProcessFactory;
import cn.bitcss.arctra.runtime.react.durable.ExecutionIncarnation;
import cn.bitcss.arctra.runtime.react.durable.OperationIds;
import cn.bitcss.arctra.runtime.react.governance.GovernanceToolCallingAdvisor;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.ai.chat.messages.AssistantMessage;

/**
 * Handles DURABLE execution paths.
 *
 * <p><strong>M6-T6.4 Execution Flow Decomposition</strong>
 *
 * <p>DURABLE execution semantics:
 *
 * <ul>
 *   <li><strong>ALLOW:</strong> Materialize RUNNABLE checkpoint → auto-continue
 *   <li><strong>REQUIRE_APPROVAL:</strong> Materialize WAITING checkpoint → return suspended
 *       process
 * </ul>
 *
 * <p><strong>Key semantic:</strong> All DURABLE paths commit checkpoint BEFORE any physical tool
 * execution. This ensures logical operations are durably reachable before side effects.
 *
 * <p><strong>Responsibility:</strong>
 *
 * <ul>
 *   <li>Generate stable processId and operationIds
 *   <li>Build and persist SuspensionCheckpoint
 *   <li>For ALLOW: delegate to shared durable continuation executor
 *   <li>For REQUIRE_APPROVAL: return durable AgentProcess
 *   <li>Emit MATERIALIZED, SUSPENDED, APPROVAL_REQUIRED events
 * </ul>
 *
 * <p><strong>M6-T6.4 Phase 10:</strong> Implements {@link ExecutionHandler} for polymorphic
 * dispatch.
 *
 * @author lov3r
 * @since M6-T6.4
 */
public class DurableExecutionHandler implements ExecutionHandler {

  private final CheckpointStore checkpointStore;
  private final String runtimeBindingKey;
  private final ExecutionEventListener executionEventSink;
  private final DurableContinuationExecutor durableContinuationExecutor;

  public DurableExecutionHandler(
      CheckpointStore checkpointStore,
      String runtimeBindingKey,
      ExecutionEventListener executionEventSink,
      DurableContinuationExecutor durableContinuationExecutor) {
    this.checkpointStore = checkpointStore;
    this.runtimeBindingKey = runtimeBindingKey;
    this.executionEventSink = executionEventSink;
    this.durableContinuationExecutor = durableContinuationExecutor;
  }

  /**
   * Handle DURABLE + ALLOW: materialize RUNNABLE checkpoint then auto-continue.
   *
   * <p><strong>M6-T6.4 Phase 6-9: ALLOW + DURABLE path.</strong>
   *
   * <p>Execution sequence:
   *
   * <ol>
   *   <li>Generate stable processId
   *   <li>Assign stable operationId to each tool call
   *   <li>Build PendingToolCall batch
   *   <li>Create RUNNABLE checkpoint (generation 1)
   *   <li>checkpointStore.create() - DURABLE COMMIT
   *   <li>Emit MATERIALIZED event
   *   <li>Delegate to shared durable continuation executor
   * </ol>
   *
   * @param suspensionState governance state with RUNNABLE disposition
   * @param evidences accumulated evidences
   * @param definition agent definition
   * @param context execution context with DURABLE mode
   * @return agent result (completed after auto-continue)
   */
  @Override
  public AgentResult handleAllow(
      GovernanceToolCallingAdvisor.SuspensionState suspensionState,
      List<Evidence> evidences,
      AgentDefinition definition,
      AgentExecutionContext context) {

    // Build and persist RUNNABLE checkpoint
    SuspensionCheckpoint checkpoint =
        buildAndPersistCheckpoint(
            suspensionState, evidences, context, ContinuationDisposition.RUNNABLE);

    String processId = checkpoint.processId();
    List<PendingToolCall> pendingBatch = checkpoint.pendingBatch();

    // Emit MATERIALIZED event
    emitEvent(
        processId,
        EventType.MATERIALIZED,
        1L,
        String.format("{\"pendingToolCount\": %d}", pendingBatch.size()));

    // Delegate to shared durable continuation executor
    try {
      return durableContinuationExecutor.executeFromCheckpoint(
          checkpoint, suspensionState, evidences, definition, context);
    } catch (Exception e) {
      // Auto-continue failed - checkpoint exists, process can be manually resumed
      emitEvent(
          processId,
          EventType.FAILED,
          1L,
          String.format(
              "{\"reason\": \"auto-continue failed\", \"error\": \"%s\"}", e.getMessage()));
      throw new RuntimeException(
          "Auto-continue failed for " + processId + ", manual resume required", e);
    }
  }

  /**
   * Handle DURABLE + REQUIRE_APPROVAL: materialize WAITING checkpoint and return suspended process.
   *
   * <p><strong>M5-T4 Phase 4 / M6-T6.4 DURABLE + REQUIRE_APPROVAL path.</strong>
   *
   * <p>Creates checkpoint-backed durable process. Durability-first: checkpoint persisted BEFORE
   * process exposed.
   *
   * <p><strong>Event wiring:</strong>
   *
   * <ul>
   *   <li>APPROVAL_REQUIRED after governance decision
   *   <li>SUSPENDED after checkpoint.create() succeeds
   * </ul>
   *
   * @param suspensionState governance suspension state
   * @param evidences accumulated evidences
   * @param definition agent definition
   * @param context execution context
   * @return suspended agent result with durable process
   */
  @Override
  public AgentResult handleRequireApproval(
      GovernanceToolCallingAdvisor.SuspensionState suspensionState,
      List<Evidence> evidences,
      AgentDefinition definition,
      AgentExecutionContext context) {

    // Build and persist WAITING checkpoint
    SuspensionCheckpoint checkpoint =
        buildAndPersistCheckpoint(
            suspensionState, evidences, context, ContinuationDisposition.WAITING_FOR_SIGNAL);

    String processId = checkpoint.processId();
    List<PendingToolCall> pendingBatch = checkpoint.pendingBatch();

    // Emit APPROVAL_REQUIRED event (governance decision = REQUIRE_APPROVAL)
    String toolNames = pendingBatch.stream().map(PendingToolCall::toolName).toList().toString();
    String approvalPayload =
        """
        {"toolNames": %s, "policyReason": "governance_requires_approval"}
        """
            .formatted(toolNames);
    emitEvent(processId, EventType.APPROVAL_REQUIRED, null, approvalPayload);

    // Emit SUSPENDED event (checkpoint.create() succeeded)
    String suspendedPayload =
        """
        {"checkpointVersion": 1, "pendingToolCount": %d, "evidenceCount": %d}
        """
            .formatted(pendingBatch.size(), evidences.size());
    emitEvent(processId, EventType.SUSPENDED, 1L, suspendedPayload);

    // ONLY after checkpoint persisted: create durable process
    AgentProcess process =
        ProcessFactory.createDurableSuspended(
            processId, 1L, durableContinuationExecutor.getEngine());

    // Return suspended result
    String toolNamesList =
        suspensionState.assistantMessageWithToolCalls().getToolCalls().stream()
            .map(AssistantMessage.ToolCall::name)
            .toList()
            .toString();
    String partialContent =
        String.format("Execution suspended: tool batch %s requires approval", toolNamesList);
    return new AgentResult(partialContent, evidences, process);
  }

  /**
   * Build and persist checkpoint (M6-T6.4 optimization: unified checkpoint construction).
   *
   * <p>Extracts common checkpoint building logic used by both ALLOW and REQUIRE_APPROVAL paths.
   *
   * @param suspensionState governance suspension state
   * @param evidences accumulated evidences
   * @param context execution context
   * @param disposition continuation disposition (RUNNABLE or WAITING_FOR_SIGNAL)
   * @return persisted checkpoint
   * @throws RuntimeException if checkpoint persistence fails
   */
  private SuspensionCheckpoint buildAndPersistCheckpoint(
      GovernanceToolCallingAdvisor.SuspensionState suspensionState,
      List<Evidence> evidences,
      AgentExecutionContext context,
      ContinuationDisposition disposition) {

    // 1. Generate stable processId
    String processId = UUID.randomUUID().toString();

    // 2. Extract sessionId from context
    String sessionId = context.sessionId();

    // 3. Build pendingBatch from suspension state
    List<PendingToolCall> pendingBatch =
        suspensionState.assistantMessageWithToolCalls().getToolCalls().stream()
            .map(
                tc ->
                    new PendingToolCall(
                        OperationIds.generate(), // operationId - framework identity
                        tc.id(), // toolCallId - protocol identity
                        tc.name(), // toolName
                        tc.arguments())) // arguments JSON
            .toList();

    // 4. Build checkpoint v1
    SuspensionCheckpoint checkpoint =
        new SuspensionCheckpoint(
            SuspensionCheckpoint.CURRENT_SCHEMA_VERSION,
            processId,
            1L, // Initial version
            runtimeBindingKey,
            sessionId,
            disposition,
            pendingBatch,
            List.copyOf(evidences), // Defensive copy
            ExecutionIncarnation.current());

    // 5. Persist checkpoint FIRST (durability-first)
    try {
      checkpointStore.create(checkpoint);
    } catch (Exception e) {
      throw new RuntimeException(
          "Failed to materialize durable checkpoint for " + processId, e);
    }

    return checkpoint;
  }

  private void emitEvent(
      String processId, EventType eventType, Long checkpointVersion, String payload) {
    executionEventSink.onEvent(
        new ExecutionEvent(processId, eventType, checkpointVersion, payload));
  }
}
