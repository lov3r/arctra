package cn.bitcss.arctra.runtime.react.execution;

import cn.bitcss.arctra.agent.AgentDefinition;
import cn.bitcss.arctra.agent.AgentExecutionContext;
import cn.bitcss.arctra.agent.AgentResult;
import cn.bitcss.arctra.evidence.Evidence;
import cn.bitcss.arctra.process.AgentProcess;
import cn.bitcss.arctra.process.ContinuationSignal;
import cn.bitcss.arctra.process.ContinuationSignal.ApprovalSignal;
import cn.bitcss.arctra.runtime.ProcessFactory;
import cn.bitcss.arctra.runtime.react.governance.GovernanceToolCallingAdvisor;
import java.util.List;

/**
 * Handles EPHEMERAL execution paths.
 *
 * <p><strong>M6-T6.4 Execution Flow Decomposition</strong>
 *
 * <p>EPHEMERAL execution semantics:
 *
 * <ul>
 *   <li><strong>ALLOW:</strong> NOT SUPPORTED (no durable state for auto-continue)
 *   <li><strong>REQUIRE_APPROVAL:</strong> Local/ephemeral suspension with continuation function
 * </ul>
 *
 * <p><strong>Key semantic:</strong> EPHEMERAL REQUIRE_APPROVAL creates local wait state with NO
 * cross-JVM recovery guarantee. Suspension state is held in-memory only.
 *
 * <p><strong>Responsibility:</strong>
 *
 * <ul>
 *   <li>Create ephemeral suspension with continuation function
 *   <li>Return suspended AgentProcess
 *   <li>Does NOT create CheckpointStore entries
 *   <li>Does NOT provide restart-durable recovery
 * </ul>
 *
 * <p><strong>M6-T6.4 Phase 10:</strong> Implements {@link ExecutionHandler} for polymorphic
 * dispatch.
 *
 * @author lov3r
 * @since M6-T6.4
 */
public class EphemeralExecutionHandler implements ExecutionHandler {

  private final ModelContinuationExecutor modelContinuationExecutor;

  public EphemeralExecutionHandler(ModelContinuationExecutor modelContinuationExecutor) {
    this.modelContinuationExecutor = modelContinuationExecutor;
  }

  /**
   * Handle ALLOW disposition (NOT SUPPORTED for ephemeral mode).
   *
   * <p>Ephemeral mode does not support ALLOW disposition because auto-continue requires durable
   * state to be materialized before physical tool execution. Without checkpoint persistence, there
   * is no recovery point for auto-continue.
   *
   * @throws UnsupportedOperationException always (ephemeral mode does not support ALLOW)
   */
  @Override
  public AgentResult handleAllow(
      GovernanceToolCallingAdvisor.SuspensionState suspensionState,
      List<Evidence> evidences,
      AgentDefinition definition,
      AgentExecutionContext context) {
    throw new UnsupportedOperationException(
        "ALLOW disposition requires durable infrastructure (CheckpointStore). "
            + "Ephemeral mode only supports REQUIRE_APPROVAL disposition.");
  }

  /**
   * Handle EPHEMERAL + REQUIRE_APPROVAL: create local ephemeral suspension.
   *
   * @param suspensionState governance suspension state
   * @param evidences accumulated evidences
   * @param definition agent definition
   * @param context execution context
   * @return suspended agent result with ephemeral process
   */
  @Override
  public AgentResult handleRequireApproval(
      GovernanceToolCallingAdvisor.SuspensionState suspensionState,
      List<Evidence> evidences,
      AgentDefinition definition,
      AgentExecutionContext context) {

    // Create continuation function for resume
    var continuationFunction =
        new java.util.function.Function<ContinuationSignal, AgentResult>() {
          @Override
          public AgentResult apply(ContinuationSignal signal) {
            if (signal instanceof ApprovalSignal approval) {
              if (approval.approved()) {
                return modelContinuationExecutor.resumeApproved(
                    suspensionState, evidences, definition, context);
              } else {
                return modelContinuationExecutor.resumeDenied(
                    suspensionState, evidences, definition, context);
              }
            }
            throw new IllegalArgumentException("Unknown ContinuationSignal type: " + signal);
          }
        };

    // Materialize ephemeral Process (no checkpoint, no durable state)
    AgentProcess process = ProcessFactory.createSuspended(continuationFunction);

    // Return suspended result - display first tool name for user message
    String toolNames =
        suspensionState.assistantMessageWithToolCalls().getToolCalls().stream()
            .map(org.springframework.ai.chat.messages.AssistantMessage.ToolCall::name)
            .toList()
            .toString();
    String partialContent =
        String.format("Execution suspended: tool batch %s requires approval", toolNames);
    return new AgentResult(partialContent, evidences, process);
  }
}
