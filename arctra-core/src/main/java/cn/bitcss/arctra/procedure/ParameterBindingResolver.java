package cn.bitcss.arctra.procedure;

import java.util.Map;
import java.util.Objects;

/**
 * 参数绑定解析器（M8-D）。
 *
 * <p>从过程执行状态中解析参数绑定，生成工具调用的实际参数值。
 *
 * <p>支持的绑定源：
 *
 * <ul>
 *   <li>INPUT: 从 boundInputs 获取
 *   <li>CONSTANT: 直接使用字面值
 *   <li>PREVIOUS_STEP_OUTPUT: 从 capturedStepOutputs 提取
 *   <li>RUNTIME_CONTEXT: V1 未实现（延迟）
 * </ul>
 *
 * @author lov3r
 * @since M8-D
 */
class ParameterBindingResolver {

  /**
   * 解析步骤的所有参数绑定。
   *
   * @param step 过程步骤
   * @param executionState 当前执行状态
   * @return 解析后的参数（参数名 -> 实际值）
   * @throws BindingResolutionException 如果任何绑定无法解析
   */
  Map<String, Object> resolve(ProcedureStep step, ProcedureExecutionState executionState)
      throws BindingResolutionException {

    Objects.requireNonNull(step, "step cannot be null");
    Objects.requireNonNull(executionState, "executionState cannot be null");

    var resolvedParams = new java.util.HashMap<String, Object>();

    for (var entry : step.parameterBindings().entrySet()) {
      String paramName = entry.getKey();
      ParameterBinding binding = entry.getValue();

      Object value = resolveBinding(binding, executionState, step.stepIndex());
      resolvedParams.put(paramName, value);
    }

    return resolvedParams;
  }

  /**
   * 解析单个绑定。
   */
  private Object resolveBinding(
      ParameterBinding binding, ProcedureExecutionState executionState, int currentStepIndex)
      throws BindingResolutionException {

    return switch (binding.source()) {
      case INPUT -> resolveInput(binding.path(), executionState);
      case CONSTANT -> resolveConstant(binding.path());
      case PREVIOUS_STEP_OUTPUT ->
          resolvePreviousStepOutput(binding.path(), executionState, currentStepIndex);
      case RUNTIME_CONTEXT -> throw new BindingResolutionException(
          "RUNTIME_CONTEXT binding not yet implemented: " + binding.path());
    };
  }

  /**
   * 解析 INPUT 绑定。
   */
  private Object resolveInput(String reference, ProcedureExecutionState executionState)
      throws BindingResolutionException {

    Object value = executionState.boundInputs().get(reference);
    if (value == null) {
      throw new BindingResolutionException("INPUT binding not found: " + reference);
    }
    return value;
  }

  /**
   * 解析 CONSTANT 绑定。
   */
  private Object resolveConstant(String reference) {
    // CONSTANT 绑定的 reference 就是字面值
    return reference;
  }

  /**
   * 解析 PREVIOUS_STEP_OUTPUT 绑定。
   *
   * <p>引用格式：stepIndex.outputName
   */
  private Object resolvePreviousStepOutput(
      String reference, ProcedureExecutionState executionState, int currentStepIndex)
      throws BindingResolutionException {

    // 解析引用：stepIndex.outputName
    String[] parts = reference.split("\\.", 2);
    if (parts.length != 2) {
      throw new BindingResolutionException(
          "Invalid PREVIOUS_STEP_OUTPUT reference format (expected stepIndex.outputName): "
              + reference);
    }

    int referencedStepIndex;
    try {
      referencedStepIndex = Integer.parseInt(parts[0]);
    } catch (NumberFormatException e) {
      throw new BindingResolutionException(
          "Invalid step index in PREVIOUS_STEP_OUTPUT reference: " + reference);
    }

    String outputName = parts[1];

    // 验证引用的步骤必须在当前步骤之前
    if (referencedStepIndex >= currentStepIndex) {
      throw new BindingResolutionException(
          "PREVIOUS_STEP_OUTPUT reference must be to a previous step (referenced: "
              + referencedStepIndex
              + ", current: "
              + currentStepIndex
              + ")");
    }

    // 获取步骤输出
    StepOutput stepOutput = executionState.capturedStepOutputs().get(referencedStepIndex);
    if (stepOutput == null) {
      throw new BindingResolutionException(
          "Step output not found for step " + referencedStepIndex);
    }

    // 提取字段
    Object value = stepOutput.getField(outputName);
    if (value == null) {
      throw new BindingResolutionException(
          "Output field '" + outputName + "' not found in step " + referencedStepIndex);
    }

    return value;
  }

  /** 绑定解析异常。 */
  static class BindingResolutionException extends Exception {
    BindingResolutionException(String message) {
      super(message);
    }
  }
}
