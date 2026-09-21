package cn.bitcss.arctra.procedure;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 候选过程提取器（从成功执行中学习）。
 *
 * <p><strong>M8-B：</strong>从成功的 ReAct 执行中提取可重用过程候选。
 *
 * <p>核心职责：
 *
 * <ul>
 *   <li>验证执行可缓存性（线性、幂等工具）
 *   <li>提取工具调用序列
 *   <li>推断参数绑定（安全捕获）
 *   <li>生成工具兼容性指纹
 *   <li>创建 ProcedureCandidate
 * </ul>
 *
 * <p><strong>V1 约束：</strong>
 *
 * <ul>
 *   <li>仅线性执行（无分支、循环）
 *   <li>仅只读/幂等工具
 *   <li>白名单绑定捕获
 *   <li>无动态模型推理
 * </ul>
 *
 * @author lov3r
 * @since M8-B
 */
public class CandidateExtractor {

  // V1 已知只读/幂等工具白名单（实际应从工具元数据获取）
  private static final Set<String> IDEMPOTENT_TOOLS =
      Set.of(
          "getService",
          "queryLogs",
          "getMetrics",
          "listResources",
          "describeResource",
          "searchDocumentation");

  /**
   * 从执行跟踪中提取候选过程。
   *
   * @param trace 执行跟踪
   * @param derivedFrom 来源执行标识（用于溯源）
   * @return 提取的候选，如果不可缓存则返回 empty
   */
  public Optional<ProcedureCandidate> extract(ExecutionTrace trace, String derivedFrom) {
    Objects.requireNonNull(trace, "trace cannot be null");
    Objects.requireNonNull(derivedFrom, "derivedFrom cannot be null");

    // 1. 验证可缓存性
    if (!isCacheable(trace)) {
      return Optional.empty();
    }

    // 2. 提取过程步骤
    List<ProcedureStep> steps = extractSteps(trace);
    if (steps.isEmpty()) {
      return Optional.empty();
    }

    // 3. 创建候选
    String candidateId = generateCandidateId();
    ProcedureScope scope = ProcedureScope.forAgent(trace.agentName());

    return Optional.of(
        new ProcedureCandidate(
            candidateId,
            scope,
            trace.intentKey(),
            steps,
            derivedFrom,
            Instant.now(),
            null // 验证结果待定
            ));
  }

  /**
   * 验证执行是否可缓存。
   *
   * <p>V1 规则：
   *
   * <ul>
   *   <li>所有工具必须是只读/幂等的
   *   <li>执行必须是线性的（已由跟踪保证）
   * </ul>
   */
  private boolean isCacheable(ExecutionTrace trace) {
    // 检查所有工具是否都是幂等的
    for (var invocation : trace.toolInvocations()) {
      if (!IDEMPOTENT_TOOLS.contains(invocation.toolName())) {
        return false; // 包含非幂等工具，不可缓存
      }
    }
    return true;
  }

  /**
   * 从跟踪中提取过程步骤。
   */
  private List<ProcedureStep> extractSteps(ExecutionTrace trace) {
    List<ProcedureStep> steps = new ArrayList<>();

    for (int i = 0; i < trace.toolInvocations().size(); i++) {
      var invocation = trace.toolInvocations().get(i);

      // 生成工具指纹
      var fingerprint =
          new ToolCompatibilityFingerprint(
              invocation.toolName(),
              invocation.inputSchemaHash());

      // 推断参数绑定
      Map<String, ParameterBinding> bindings = inferBindings(invocation, i, steps);

      // 提取输出
      List<OutputExtraction> outputs = extractOutputs(invocation);

      steps.add(new ProcedureStep(i, invocation.toolName(), fingerprint, bindings, outputs));
    }

    return steps;
  }

  /**
   * 推断参数绑定。
   *
   * <p>V1 简化：尝试从实际参数值推断绑定结构。
   */
  private Map<String, ParameterBinding> inferBindings(
      ExecutionTrace.ToolInvocation invocation, int stepIndex, List<ProcedureStep> previousSteps) {

    Map<String, ParameterBinding> bindings = new HashMap<>();

    // V1 简化：解析 JSON 参数
    // 实际实现应使用 JSON 解析器
    Map<String, String> params = parseJsonParams(invocation.arguments());

    for (Map.Entry<String, String> entry : params.entrySet()) {
      String paramName = entry.getKey();
      String paramValue = entry.getValue();

      // 推断绑定类型
      ParameterBinding binding = inferBinding(paramName, paramValue, stepIndex);

      // 验证绑定可捕获性
      if (isCaptureable(paramName, paramValue, binding)) {
        bindings.put(paramName, binding);
      }
      // 否则跳过此参数（不安全捕获）
    }

    return bindings;
  }

  /**
   * 推断单个参数的绑定。
   */
  private ParameterBinding inferBinding(String paramName, String paramValue, int currentStep) {
    // 检查是否是前置步骤输出引用
    if (paramValue.matches("\\d+\\..+") && currentStep > 0) {
      // 格式：stepIndex.outputName
      return new ParameterBinding(BindingSource.PREVIOUS_STEP_OUTPUT, paramValue);
    }

    // 检查是否是简单标识符（可能是 INPUT 引用）
    if (BindingCaptureValidator.looksLikeReference(paramValue)) {
      return new ParameterBinding(BindingSource.INPUT, paramValue);
    }

    // 否则视为常量
    return new ParameterBinding(BindingSource.CONSTANT, paramValue);
  }

  /**
   * 验证绑定是否可安全捕获。
   */
  private boolean isCaptureable(String paramName, String paramValue, ParameterBinding binding) {
    boolean isConstant = (binding.source() == BindingSource.CONSTANT);

    BindingCapturability capturability =
        BindingCaptureValidator.evaluateCapturability(paramName, paramValue, isConstant);

    return capturability != BindingCapturability.NEVER_CAPTURE;
  }

  /**
   * 提取输出。
   *
   * <p>V1 简化：为成功结果创建通用输出提取。
   */
  private List<OutputExtraction> extractOutputs(ExecutionTrace.ToolInvocation invocation) {
    // V1 简化：假设工具返回 JSON 对象，提取常见字段
    // 实际应基于工具元数据和结果结构

    List<OutputExtraction> outputs = new ArrayList<>();

    // 如果结果包含 "id" 字段，提取它
    if (invocation.result().contains("\"id\"")) {
      outputs.add(new OutputExtraction("id", "/id"));
    }

    return outputs;
  }

  /**
   * 生成候选 ID。
   *
   * <p>V1 简化：使用 UUID。实际可能需要更有意义的标识符。
   */
  private String generateCandidateId() {
    return "candidate-" + UUID.randomUUID().toString().substring(0, 8);
  }

  /**
   * 解析 JSON 参数（V1 简化实现）。
   *
   * <p>实际应使用真正的 JSON 解析器。
   */
  private Map<String, String> parseJsonParams(String jsonArgs) {
    Map<String, String> params = new HashMap<>();

    // V1 极简解析：仅处理简单 JSON 对象
    // 实际实现应使用 Jackson 或 Gson
    if (jsonArgs == null || jsonArgs.isBlank()) {
      return params;
    }

    // 去除大括号
    String content = jsonArgs.trim().replaceAll("^\\{|\\}$", "");

    // 分割键值对
    for (String pair : content.split(",")) {
      String[] kv = pair.split(":", 2);
      if (kv.length == 2) {
        String key = kv[0].trim().replaceAll("^\"|\"$", "");
        String value = kv[1].trim().replaceAll("^\"|\"$", "");
        params.put(key, value);
      }
    }

    return params;
  }
}
