package cn.bitcss.arctra.procedure;

import cn.bitcss.arctra.agent.AgentRequest;
import cn.bitcss.arctra.execution.EventType;
import cn.bitcss.arctra.execution.ExecutionLedger;
import cn.bitcss.arctra.execution.ExecutionRecord;
import java.util.*;

/**
 * 从成功执行中提取可重用过程候选（M8-B）。
 *
 * <p><strong>M8 集成：</strong>从 ExecutionLedger 的 TOOL_EXECUTED 事件中学习步骤序列。
 *
 * <p><strong>提取逻辑：</strong>
 *
 * <ol>
 *   <li>从 ExecutionLedger 查询 TOOL_EXECUTED 事件
 *   <li>按 sequence 排序（执行顺序）
 *   <li>从 payload 解析工具名称和参数
 *   <li>为每个工具调用创建 ProcedureStep
 *   <li>返回 ProcedureCandidate
 * </ol>
 *
 * <p><strong>V1 简化：</strong>
 *
 * <ul>
 *   <li>仅提取成功执行（TOOL_EXECUTED 事件）
 *   <li>简单 JSON 解析（应使用 Jackson）
 *   <li>所有参数推断为 INPUT 绑定
 *   <li>intentKey = agentName（不做复杂意图解析）
 * </ul>
 *
 * <p><strong>TOOL_EXECUTED payload 格式：</strong>
 *
 * <pre>
 * {
 *   "operationId": "op-xxx",
 *   "toolCallId": "call-xxx",
 *   "toolName": "getService",
 *   "arguments": "{\"serviceName\":\"api\"}",
 *   "result": "success result"
 * }
 * </pre>
 *
 * @author lov3r
 * @since M8-Integration
 */
public class ProcedureCandidateExtractor {

  private final ToolSchemaFingerprintGenerator fingerprintGenerator;

  public ProcedureCandidateExtractor(ToolSchemaFingerprintGenerator fingerprintGenerator) {
    this.fingerprintGenerator =
        Objects.requireNonNull(fingerprintGenerator, "fingerprintGenerator cannot be null");
  }

  /**
   * 从成功执行中提取候选过程。
   *
   * @param ledger 执行历史
   * @param processId 进程 ID
   * @param request 原始请求
   * @param agentName agent 名称（用于 scope）
   * @return 提取的候选过程，如果无法提取则返回空
   */
  public Optional<ProcedureCandidate> extract(
      ExecutionLedger ledger, String processId, AgentRequest request, String agentName) {

    Objects.requireNonNull(ledger, "ledger cannot be null");
    Objects.requireNonNull(processId, "processId cannot be null");
    Objects.requireNonNull(request, "request cannot be null");
    Objects.requireNonNull(agentName, "agentName cannot be null");

    // 查询所有 TOOL_EXECUTED 事件
    List<ExecutionRecord> toolEvents = ledger.queryByProcess(processId).stream()
        .filter(record -> record.eventType() == EventType.TOOL_EXECUTED)
        .sorted(Comparator.comparingLong(ExecutionRecord::sequence))
        .toList();

    // V1 简化：至少需要 1 个工具调用
    if (toolEvents.isEmpty()) {
      return Optional.empty();
    }

    // 转换为 ProcedureStep
    List<ProcedureStep> steps = new ArrayList<>();
    for (int i = 0; i < toolEvents.size(); i++) {
      ExecutionRecord record = toolEvents.get(i);

      // 从 payload 解析工具信息
      ToolExecutionInfo toolInfo = parseToolExecutionPayload(record.payload());
      if (toolInfo == null) {
        // 解析失败，跳过
        continue;
      }

      // 生成工具兼容性指纹（V1 简化：使用参数作为 schema）
      String fingerprint = ToolSchemaFingerprintGenerator.generateFingerprint(toolInfo.arguments);
      ToolCompatibilityFingerprint toolFingerprint =
          new ToolCompatibilityFingerprint(toolInfo.toolName, fingerprint);

      // V1 简化：所有参数都推断为 INPUT 绑定
      Map<String, ParameterBinding> bindings = inferParameterBindings(toolInfo.arguments);

      // V1 简化：无输出提取
      List<OutputExtraction> outputs = List.of();

      ProcedureStep step = new ProcedureStep(
          i, toolInfo.toolName, toolFingerprint, bindings, outputs);
      steps.add(step);
    }

    if (steps.isEmpty()) {
      return Optional.empty();
    }

    // 生成 procedureId
    String procedureId = generateProcedureId(agentName);

    // V1 简化：intentKey = agentName
    String intentKey = agentName;

    // 创建 ProcedureScope
    ProcedureScope scope = ProcedureScope.forAgent(agentName);

    // 创建候选
    ProcedureCandidate candidate = new ProcedureCandidate(
        procedureId,
        scope,
        intentKey,
        steps,
        processId, // derivedFrom (executionId)
        java.time.Instant.now(), // createdAt
        null // validationOutcome (未验证)
    );

    return Optional.of(candidate);
  }

  /**
   * 从 TOOL_EXECUTED payload 解析工具执行信息。
   *
   * <p>V1 极简 JSON 解析（应使用 Jackson）。
   */
  private ToolExecutionInfo parseToolExecutionPayload(String payload) {
    if (payload == null || payload.isBlank()) {
      return null;
    }

    try {
      // 提取 toolName
      String toolName = extractJsonField(payload, "toolName");
      if (toolName == null) {
        return null;
      }

      // 提取 arguments（嵌套 JSON 字符串）
      String arguments = extractJsonField(payload, "arguments");
      if (arguments == null) {
        arguments = "{}";
      }

      return new ToolExecutionInfo(toolName, arguments);
    } catch (Exception e) {
      return null;
    }
  }

  /**
   * 极简 JSON 字段提取（V1 简化）。
   */
  private String extractJsonField(String json, String fieldName) {
    // 查找 "fieldName": "value" 或 "fieldName": {...}
    String pattern = "\"" + fieldName + "\":";
    int patternIndex = json.indexOf(pattern);
    if (patternIndex == -1) {
      return null;
    }

    int startIndex = patternIndex + pattern.length();

    // 跳过空格
    while (startIndex < json.length() && Character.isWhitespace(json.charAt(startIndex))) {
      startIndex++;
    }

    if (startIndex >= json.length()) {
      return null;
    }

    char firstChar = json.charAt(startIndex);

    if (firstChar == '"') {
      // 字符串值 - 需要处理转义的引号
      int endIndex = startIndex + 1;
      while (endIndex < json.length()) {
        char c = json.charAt(endIndex);
        if (c == '"' && json.charAt(endIndex - 1) != '\\') {
          // 找到未转义的结束引号
          return json.substring(startIndex + 1, endIndex);
        }
        endIndex++;
      }
      return null;
    } else if (firstChar == '{') {
      // 对象值 - 查找匹配的 }
      int depth = 1;
      int endIndex = startIndex + 1;
      while (endIndex < json.length() && depth > 0) {
        char c = json.charAt(endIndex);
        if (c == '{') {
          depth++;
        } else if (c == '}') {
          depth--;
        }
        endIndex++;
      }
      return json.substring(startIndex, endIndex);
    } else {
      // 其他类型（数字、布尔值等）
      int endIndex = startIndex;
      while (endIndex < json.length()) {
        char c = json.charAt(endIndex);
        if (c == ',' || c == '}' || c == ']') {
          break;
        }
        endIndex++;
      }
      return json.substring(startIndex, endIndex).trim();
    }
  }

  /**
   * 推断参数绑定（V1 简化：所有参数都是 INPUT）。
   */
  private Map<String, ParameterBinding> inferParameterBindings(String argumentsJson) {
    Map<String, ParameterBinding> bindings = new HashMap<>();

    // 去除转义（arguments 是嵌套的 JSON 字符串，包含转义）
    String unescaped = argumentsJson.replace("\\\"", "\"");

    // 简单正则提取 "key": value 模式
    java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\"([^\"]+)\"\\s*:");
    java.util.regex.Matcher matcher = pattern.matcher(unescaped);

    while (matcher.find()) {
      String paramName = matcher.group(1);
      bindings.put(paramName, new ParameterBinding(BindingSource.INPUT, paramName));
    }

    return bindings;
  }

  /**
   * 生成唯一 procedureId。
   */
  private String generateProcedureId(String agentName) {
    return agentName + "-proc-" + System.currentTimeMillis();
  }

  /**
   * 工具执行信息（内部辅助类）。
   */
  private record ToolExecutionInfo(String toolName, String arguments) {}
}
