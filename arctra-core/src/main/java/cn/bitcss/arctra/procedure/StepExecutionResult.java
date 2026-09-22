package cn.bitcss.arctra.procedure;

import cn.bitcss.arctra.checkpoint.PendingToolCall;

/**
 * Step execution result (M8-Phase3.4).
 *
 * <p>Represents the outcome of preparing a procedure step for execution.
 *
 * <p><strong>Result types:</strong>
 *
 * <ul>
 *   <li><strong>ALLOWED:</strong> Step approved by governance, ready to execute
 *   <li><strong>REQUIRES_APPROVAL:</strong> Step requires approval before execution
 *   <li><strong>COMPLETED:</strong> All steps completed, no more steps to execute
 * </ul>
 *
 * @author lov3r
 * @since M8-Phase3.4
 */
public class StepExecutionResult {
  private final ResultType type;
  private final PendingToolCall pendingCall; // nullable

  private StepExecutionResult(ResultType type, PendingToolCall pendingCall) {
    this.type = type;
    this.pendingCall = pendingCall;
  }

  public static StepExecutionResult allowed(PendingToolCall pendingCall) {
    return new StepExecutionResult(ResultType.ALLOWED, pendingCall);
  }

  public static StepExecutionResult requiresApproval(PendingToolCall pendingCall) {
    return new StepExecutionResult(ResultType.REQUIRES_APPROVAL, pendingCall);
  }

  public static StepExecutionResult completed() {
    return new StepExecutionResult(ResultType.COMPLETED, null);
  }

  public ResultType type() {
    return type;
  }

  public PendingToolCall pendingCall() {
    if (pendingCall == null) {
      throw new IllegalStateException("No pending call for result type: " + type);
    }
    return pendingCall;
  }

  public boolean isCompleted() {
    return type == ResultType.COMPLETED;
  }

  public boolean isAllowed() {
    return type == ResultType.ALLOWED;
  }

  public boolean requiresApproval() {
    return type == ResultType.REQUIRES_APPROVAL;
  }

  public enum ResultType {
    ALLOWED,
    REQUIRES_APPROVAL,
    COMPLETED
  }
}
