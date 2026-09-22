package cn.bitcss.arctra.procedure;

import java.util.Objects;

/**
 * 过程执行处理器（M8 集成 - 阶段 3.2）。
 *
 * <p><strong>核心职责：</strong>协调缓存过程的执行流程，集成 ProcedureExecutionCoordinator。
 *
 * <p><strong>执行流程：</strong>
 *
 * <pre>
 * 1. executeNextStep(procedure, executionState)
 *    → 调用 coordinator.prepareNextStep()
 *    → 返回 StepExecutionResult (ALLOWED/REQUIRES_APPROVAL/COMPLETED)
 *
 * 2. 调用者根据结果：
 *    - ALLOWED: 执行 pendingCall，然后调用 advanceAfterSuccess()
 *    - REQUIRES_APPROVAL: 创建 checkpoint，暂停
 *    - COMPLETED: 完成执行
 *
 * 3. advanceAfterSuccess(executionState, step, toolResult)
 *    → 捕获输出
 *    → 推进到下一步
 *    → 返回新的 executionState
 * </pre>
 *
 * <p><strong>V1 简化：</strong>
 *
 * <ul>
 *   <li>不处理工具执行本身（由调用者负责）
 *   <li>不处理 checkpoint 创建（由调用者负责）
 *   <li>不处理失败回退（延迟到集成测试）
 * </ul>
 *
 * @author lov3r
 * @since M8-Integration
 */
public class ProcedureExecutionHandler {

  private final ProcedureExecutionCoordinator coordinator;

  public ProcedureExecutionHandler(ProcedureExecutionCoordinator coordinator) {
    this.coordinator = Objects.requireNonNull(coordinator, "coordinator cannot be null");
  }

  /**
   * 执行下一个步骤。
   *
   * <p>准备下一步的执行，返回 StepExecutionResult。
   *
   * @param procedure 正在执行的过程
   * @param executionState 当前执行状态
   * @return 步骤执行结果
   * @throws ProcedureGovernanceException 如果步骤被治理策略拒绝
   * @throws ParameterBindingResolver.BindingResolutionException 如果参数绑定无法解析
   * @throws IllegalArgumentException 如果 procedure 和 executionState 不匹配
   */
  public StepExecutionResult executeNextStep(
      ReusableProcedure procedure, ProcedureExecutionState executionState)
      throws ProcedureGovernanceException, ParameterBindingResolver.BindingResolutionException {

    Objects.requireNonNull(procedure, "procedure cannot be null");
    Objects.requireNonNull(executionState, "executionState cannot be null");

    // 委托给 coordinator
    return coordinator.prepareNextStep(procedure, executionState);
  }

  /**
   * 在步骤成功执行后推进状态。
   *
   * <p>捕获步骤输出，推进到下一步。
   *
   * @param executionState 当前执行状态
   * @param step 刚执行的步骤
   * @param toolResult 工具执行结果（JSON 字符串）
   * @return 新的执行状态（推进到下一步，包含捕获的输出）
   */
  public ProcedureExecutionState advanceAfterSuccess(
      ProcedureExecutionState executionState, ProcedureStep step, String toolResult) {

    Objects.requireNonNull(executionState, "executionState cannot be null");
    Objects.requireNonNull(step, "step cannot be null");
    Objects.requireNonNull(toolResult, "toolResult cannot be null");

    // 捕获步骤输出
    StepOutput output = coordinator.captureStepOutput(step, toolResult);

    // 推进到下一步并保存输出
    return executionState.advanceStepWithOutput(output);
  }
}
