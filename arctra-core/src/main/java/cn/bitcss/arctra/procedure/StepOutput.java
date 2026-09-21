package cn.bitcss.arctra.procedure;

import java.util.Map;
import java.util.Objects;

/**
 * 步骤输出（从工具执行结果中提取）。
 *
 * <p><strong>M8-D：</strong>捕获的步骤输出，用于 PREVIOUS_STEP_OUTPUT 绑定。
 *
 * <p>包含从工具执行结果中提取的命名字段。
 *
 * @param extractedFields 提取的字段（字段名 -> 值）
 * @author lov3r
 * @since M8-D
 */
public record StepOutput(Map<String, Object> extractedFields) {

  public StepOutput {
    Objects.requireNonNull(extractedFields, "extractedFields cannot be null");
    // 防御性复制（不可变）
    extractedFields = Map.copyOf(extractedFields);
  }

  /**
   * 从原始工具结果中提取输出。
   *
   * <p>V1 简化：假设工具结果是 JSON 对象，提取所有顶层字段。
   *
   * @param toolResult 工具执行结果（JSON 字符串）
   * @param extractions 输出提取规则
   * @return 步骤输出
   */
  public static StepOutput extract(String toolResult, java.util.List<OutputExtraction> extractions) {
    Objects.requireNonNull(toolResult, "toolResult cannot be null");
    Objects.requireNonNull(extractions, "extractions cannot be null");

    // V1 简化：手动 JSON 解析（实际应使用 Jackson）
    var fields = new java.util.HashMap<String, Object>();

    for (var extraction : extractions) {
      String fieldName = extraction.outputName();
      String jsonPath = extraction.jsonPath();

      // V1 极简 JSON 路径解析：仅支持 "/fieldName"
      if (jsonPath.startsWith("/")) {
        String key = jsonPath.substring(1);
        String value = extractJsonField(toolResult, key);
        if (value != null) {
          fields.put(fieldName, value);
        }
      }
    }

    return new StepOutput(fields);
  }

  /**
   * 获取字段值。
   *
   * @param fieldName 字段名称
   * @return 字段值，如果不存在则返回 null
   */
  public Object getField(String fieldName) {
    return extractedFields.get(fieldName);
  }

  /**
   * V1 极简 JSON 字段提取。
   *
   * @param json JSON 字符串
   * @param key 字段键
   * @return 字段值（字符串），如果不存在则返回 null
   */
  private static String extractJsonField(String json, String key) {
    // V1 极简实现：查找 "key":"value" 或 "key":value
    String pattern = "\"" + key + "\"\\s*:\\s*\"([^\"]+)\"";
    java.util.regex.Pattern p = java.util.regex.Pattern.compile(pattern);
    java.util.regex.Matcher m = p.matcher(json);
    if (m.find()) {
      return m.group(1);
    }

    // 尝试不带引号的值
    pattern = "\"" + key + "\"\\s*:\\s*([^,}]+)";
    p = java.util.regex.Pattern.compile(pattern);
    m = p.matcher(json);
    if (m.find()) {
      return m.group(1).trim();
    }

    return null;
  }
}
