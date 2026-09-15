package cn.bitcss.arctra.runtime.react;

import cn.bitcss.arctra.checkpoint.PendingToolCall;
import cn.bitcss.arctra.evidence.Evidence;
import java.util.List;

/**
 * Execution outcome from Spring AI resumed execution.
 *
 * <p>Represents the MECHANISM outcome of provider-specific resumed execution, NOT the durable state
 * transition truth.
 *
 * <h2>Semantic Distinction</h2>
 *
 * <p><strong>ModelCompleted</strong> means:
 *
 * <ul>
 *   <li>Spring AI model/tool execution finished normally
 *   <li>Final assistant content generated
 *   <li>No further tool calls require approval
 * </ul>
 *
 * <p><strong>Does NOT mean</strong>:
 *
 * <ul>
 *   <li>Durable process is COMPLETED (requires CHECK B deleteIfVersion)
 *   <li>ChatMemory persistence succeeded (separate post-commit action)
 *   <li>COMPLETED event emitted (orchestration layer responsibility)
 * </ul>
 *
 * <p><strong>GovernanceSuspended</strong> means:
 *
 * <ul>
 *   <li>Spring AI model returned new tool calls
 *   <li>Governance policy requires approval
 *   <li>Provider execution suspended with pending batch
 * </ul>
 *
 * <p><strong>Does NOT mean</strong>:
 *
 * <ul>
 *   <li>Durable process is SUSPENDED (requires CHECK B replaceIfVersion)
 *   <li>Checkpoint persisted (orchestration layer responsibility)
 *   <li>APPROVAL_REQUIRED / SUSPENDED events emitted (orchestration layer responsibility)
 * </ul>
 *
 * <h2>Framework-Neutral Boundary</h2>
 *
 * <p>This outcome type MUST NOT contain Spring AI protocol types. All conversions from
 * Spring AI types (AssistantMessage, ToolCall, etc.) to framework-neutral types
 * (PendingToolCall) occur inside SpringAiResumedExecutionHandler.
 *
 * <p>This allows durable orchestration (DurableResumeCoordinator) to remain
 * provider-independent.
 *
 * @author lov3r
 */
sealed interface ResumedExecutionOutcome permits ResumedExecutionOutcome.ModelCompleted,
    ResumedExecutionOutcome.GovernanceSuspended {

  /**
   * Accumulated evidences from resumed execution.
   *
   * <p>Includes both checkpoint evidences and new evidences from tool execution.
   */
  List<Evidence> evidences();

  /**
   * Model execution completed normally without further approval requirements.
   *
   * @param content final assistant content from model
   * @param evidences accumulated evidences (checkpoint + new)
   */
  record ModelCompleted(String content, List<Evidence> evidences)
      implements ResumedExecutionOutcome {}

  /**
   * Model execution encountered new tool calls requiring approval.
   *
   * <p>Contains framework-neutral pending batch for checkpoint construction by orchestration
   * layer.
   *
   * <p><strong>IMPORTANT</strong>: This outcome carries {@link PendingToolCall} (framework-neutral)
   * instead of Spring AI {@code AssistantMessage}. The conversion from Spring AI protocol types
   * occurs inside the handler boundary.
   *
   * @param pendingBatch new tool calls requiring approval (framework-neutral)
   * @param evidences accumulated evidences (checkpoint + new)
   */
  record GovernanceSuspended(List<PendingToolCall> pendingBatch, List<Evidence> evidences)
      implements ResumedExecutionOutcome {

    public GovernanceSuspended {
      if (pendingBatch == null) {
        throw new IllegalArgumentException("pendingBatch cannot be null");
      }
      if (evidences == null) {
        throw new IllegalArgumentException("evidences cannot be null");
      }
      // Defensive copy
      pendingBatch = List.copyOf(pendingBatch);
      evidences = List.copyOf(evidences);
    }
  }
}
