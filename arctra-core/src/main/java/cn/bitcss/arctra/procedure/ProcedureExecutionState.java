package cn.bitcss.arctra.procedure;

import java.util.Map;
import java.util.Objects;

/**
 * 过程执行状态（M8-D Checkpoint 扩展）。
 *
 * <p><strong>M8 Checkpoint Schema 1.3：</strong>SuspensionCheckpoint 的可选 procedureState 字段。
 *
 * <p>当 checkpoint 代表过程执行中的暂停时，此状态跟踪：
 *
 * <ul>
 *   <li>正在执行的过程标识和修订
 *   <li>当前步骤索引
 *   <li>绑定的输入参数
 *   <li>已捕获的步骤输出
 * </ul>
 *
 * <p><strong>关键语义：</strong>CheckpointStore 是当前继续权威，包括过程位置。
 *
 * @param procedureId 正在执行的过程 ID
 * @param procedureRevision 过程修订号
 * @param currentStepIndex 当前步骤索引（下一个要执行的步骤）
 * @param boundInputs 绑定的输入参数（INPUT 绑定的值）
 * @param capturedStepOutputs 已捕获的步骤输出（stepIndex -> StepOutput）
 * @author lov3r
 * @since M8-D
 */
public record ProcedureExecutionState(
    String procedureId,
    int procedureRevision,
    int currentStepIndex,
    Map<String, Object> boundInputs,
    Map<Integer, StepOutput> capturedStepOutputs) {

  public ProcedureExecutionState {
    Objects.requireNonNull(procedureId, "procedureId cannot be null");
    if (procedureId.isBlank()) {
      throw new IllegalArgumentException("procedureId cannot be blank");
    }
    if (procedureRevision < 1) {
      throw new IllegalArgumentException(
          "procedureRevision must be positive (got: " + procedureRevision + ")");
    }
    if (currentStepIndex < 0) {
      throw new IllegalArgumentException(
          "currentStepIndex cannot be negative (got: " + currentStepIndex + ")");
    }
    Objects.requireNonNull(boundInputs, "boundInputs cannot be null");
    Objects.requireNonNull(capturedStepOutputs, "capturedStepOutputs cannot be null");

    // 防御性复制（不可变）
    boundInputs = Map.copyOf(boundInputs);
    capturedStepOutputs = Map.copyOf(capturedStepOutputs);
  }

  /**
   * 创建初始过程执行状态（步骤 0，无输出）。
   *
   * @param procedureId 过程 ID
   * @param procedureRevision 过程修订号
   * @param boundInputs 绑定的输入参数
   * @return 初始执行状态
   */
  public static ProcedureExecutionState initial(
      String procedureId, int procedureRevision, Map<String, Object> boundInputs) {
    return new ProcedureExecutionState(procedureId, procedureRevision, 0, boundInputs, Map.of());
  }

  /**
   * 推进到下一步骤。
   *
   * @return 新的执行状态（currentStepIndex + 1）
   */
  public ProcedureExecutionState advanceStep() {
    return new ProcedureExecutionState(
        procedureId, procedureRevision, currentStepIndex + 1, boundInputs, capturedStepOutputs);
  }

  /**
   * 推进到下一步骤并捕获当前步骤的输出。
   *
   * @param output 当前步骤的输出
   * @return 新的执行状态（currentStepIndex + 1，包含新输出）
   */
  public ProcedureExecutionState advanceStepWithOutput(StepOutput output) {
    Objects.requireNonNull(output, "output cannot be null");

    var newOutputs = new java.util.HashMap<>(capturedStepOutputs);
    newOutputs.put(currentStepIndex, output);

    return new ProcedureExecutionState(
        procedureId, procedureRevision, currentStepIndex + 1, boundInputs, Map.copyOf(newOutputs));
  }

  /**
   * 检查是否已完成所有步骤。
   *
   * @param totalSteps 过程总步骤数
   * @return true 如果 currentStepIndex >= totalSteps
   */
  public boolean isComplete(int totalSteps) {
    return currentStepIndex >= totalSteps;
  }
}
