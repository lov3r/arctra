package cn.bitcss.arctra.procedure;

import cn.bitcss.arctra.checkpoint.PendingToolCall;
import cn.bitcss.arctra.checkpoint.SuspensionCheckpoint;
import cn.bitcss.arctra.governance.GovernanceDecision;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 过程执行协调器（M8-D）。
 *
 * <p><strong>核心职责：</strong>逐步执行可重用过程，协调参数绑定、治理评估、工具调用和输出捕获。
 *
 * <p><strong>M8-D 逐步执行循环：</strong>
 *
 * <pre>
 * 对于每个步骤：
 *   1. 解析参数绑定（INPUT/CONSTANT/PREVIOUS_STEP_OUTPUT）
 *   2. 评估治理决策（使用当前策略）
 *   3. 根据决策：
 *      - ALLOW: 生成 PendingToolCall，继续
 *      - REQUIRE_APPROVAL: 创建 WAITING checkpoint，暂停
 *      - DENY: 抛出 ProcedureGovernanceException，失效
 *   4. （工具执行后）捕获步骤输出
 *   5. 推进到下一步
 * </pre>
 *
 * <p><strong>关键语义：</strong>
 *
 * <ul>
 *   <li>一次执行一个步骤（不是批量物化）
 *   <li>CheckpointStore 是继续权威（包括 procedureState）
 *   <li>治理是当前决策（不是历史决策）
 *   <li>REQUIRE_APPROVAL 触发暂停（与 ReAct 相同）
 * </ul>
 *
 * @author lov3r
 * @since M8-D
 */
class ProcedureExecutionCoordinator {

  private final OperationGovernanceEvaluator governanceEvaluator;
  private final ParameterBindingResolver bindingResolver;

  ProcedureExecutionCoordinator(
      OperationGovernanceEvaluator governanceEvaluator,
      ParameterBindingResolver bindingResolver) {
    this.governanceEvaluator =
        Objects.requireNonNull(governanceEvaluator, "governanceEvaluator cannot be null");
    this.bindingResolver =
        Objects.requireNonNull(bindingResolver, "bindingResolver cannot be null");
  }

  /**
   * 准备下一个步骤的执行。
   *
   * <p>从当前执行状态生成下一步的 PendingToolCall，或者检测完成/暂停。
   *
   * @param procedure 正在执行的过程
   * @param executionState 当前执行状态
   * @return 步骤执行结果
   * @throws ProcedureGovernanceException 如果步骤被治理策略拒绝
   * @throws ParameterBindingResolver.BindingResolutionException 如果参数绑定无法解析
   */
  StepExecutionResult prepareNextStep(
      ReusableProcedure procedure, ProcedureExecutionState executionState)
      throws ProcedureGovernanceException, ParameterBindingResolver.BindingResolutionException {

    Objects.requireNonNull(procedure, "procedure cannot be null");
    Objects.requireNonNull(executionState, "executionState cannot be null");

    // 验证过程标识匹配
    if (!procedure.procedureId().equals(executionState.procedureId())) {
      throw new IllegalArgumentException(
          "Procedure ID mismatch: expected "
              + executionState.procedureId()
              + ", got "
              + procedure.procedureId());
    }

    if (procedure.revision() != executionState.procedureRevision()) {
      throw new IllegalArgumentException(
          "Procedure revision mismatch: expected "
              + executionState.procedureRevision()
              + ", got "
              + procedure.revision());
    }

    // 检查是否完成
    if (executionState.isComplete(procedure.steps().size())) {
      return StepExecutionResult.completed();
    }

    // 获取当前步骤
    ProcedureStep currentStep = procedure.steps().get(executionState.currentStepIndex());

    // 1. 解析参数绑定
    Map<String, Object> resolvedParams = bindingResolver.resolve(currentStep, executionState);

    // 2. 转换为 JSON 字符串（V1 简化）
    String argumentsJson = serializeParameters(resolvedParams);

    // 3. 评估治理决策
    GovernanceDecision decision = governanceEvaluator.evaluate(currentStep, argumentsJson);

    // 4. 根据决策处理
    return switch (decision) {
      case ALLOW -> prepareAllowedStep(currentStep, argumentsJson);
      case REQUIRE_APPROVAL -> prepareApprovalRequiredStep(currentStep, argumentsJson);
      case DENY -> throw new ProcedureGovernanceException(
          procedure.procedureId(),
          procedure.revision(),
          executionState.currentStepIndex(),
          currentStep.toolName(),
          "Governance policy denied step execution");
    };
  }

  /**
   * 准备允许执行的步骤。
   */
  private StepExecutionResult prepareAllowedStep(ProcedureStep step, String argumentsJson) {
    // 生成 operationId（框架拥有的逻辑操作标识）
    String operationId = generateOperationId();

    // 生成 toolCallId（Spring AI 协议标识）
    String toolCallId = generateToolCallId();

    // 创建 PendingToolCall
    PendingToolCall pendingCall =
        new PendingToolCall(operationId, toolCallId, step.toolName(), argumentsJson);

    return StepExecutionResult.allowed(pendingCall);
  }

  /**
   * 准备需要批准的步骤。
   */
  private StepExecutionResult prepareApprovalRequiredStep(
      ProcedureStep step, String argumentsJson) {
    // 生成标识
    String operationId = generateOperationId();
    String toolCallId = generateToolCallId();

    // 创建 PendingToolCall
    PendingToolCall pendingCall =
        new PendingToolCall(operationId, toolCallId, step.toolName(), argumentsJson);

    return StepExecutionResult.requiresApproval(pendingCall);
  }

  /**
   * 捕获步骤执行的输出。
   *
   * @param step 执行的步骤
   * @param toolResult 工具执行结果（JSON 字符串）
   * @return 捕获的步骤输出
   */
  StepOutput captureStepOutput(ProcedureStep step, String toolResult) {
    Objects.requireNonNull(step, "step cannot be null");
    Objects.requireNonNull(toolResult, "toolResult cannot be null");

    return StepOutput.extract(toolResult, step.outputExtractions());
  }

  /**
   * 生成 operationId（框架逻辑操作标识）。
   */
  private String generateOperationId() {
    return "op-" + UUID.randomUUID().toString().substring(0, 8);
  }

  /**
   * 生成 toolCallId（Spring AI 协议标识）。
   */
  private String generateToolCallId() {
    return "call-" + UUID.randomUUID().toString().substring(0, 8);
  }

  /**
   * 序列化参数为 JSON（V1 简化实现）。
   */
  private String serializeParameters(Map<String, Object> params) {
    if (params.isEmpty()) {
      return "{}";
    }

    // V1 极简 JSON 序列化（实际应使用 Jackson）
    var json = new StringBuilder("{");
    boolean first = true;
    for (var entry : params.entrySet()) {
      if (!first) {
        json.append(",");
      }
      first = false;

      String key = entry.getKey();
      Object value = entry.getValue();

      json.append("\"").append(escapeJson(key)).append("\":");

      if (value instanceof String) {
        json.append("\"").append(escapeJson((String) value)).append("\"");
      } else if (value instanceof Number || value instanceof Boolean) {
        json.append(value);
      } else {
        // 其他类型转为字符串
        json.append("\"").append(escapeJson(String.valueOf(value))).append("\"");
      }
    }
    json.append("}");

    return json.toString();
  }

  /**
   * 转义 JSON 字符串。
   */
  private String escapeJson(String value) {
    return value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t");
  }

  /** 步骤执行结果。 */
  public static class StepExecutionResult {
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
}
