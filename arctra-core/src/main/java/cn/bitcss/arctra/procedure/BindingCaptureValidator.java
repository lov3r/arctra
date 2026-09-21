package cn.bitcss.arctra.procedure;

import java.util.Set;

/**
 * 绑定捕获安全性验证器。
 *
 * <p><strong>M8-B V1 白名单方法：</strong>只捕获显式安全的绑定。
 *
 * <p>验证参数绑定是否可以安全地包含在可重用过程中，不会泄露秘密或动态运行时值。
 *
 * @author lov3r
 * @since M8-B
 */
class BindingCaptureValidator {

  // V1 允许的常量值白名单（环境标识符等）
  private static final Set<String> ALLOWED_CONSTANTS =
      Set.of("prod", "staging", "dev", "production", "development", "test");

  // V1 疑似秘密的参数名称模式（永不捕获）
  private static final Set<String> SUSPECTED_SECRET_PARAM_NAMES =
      Set.of(
          "password",
          "secret",
          "token",
          "apikey",
          "api_key",
          "apiKey",
          "privatekey",
          "private_key",
          "privateKey",
          "credential",
          "credentials",
          "auth",
          "authorization");

  /**
   * 评估参数绑定的可捕获性。
   *
   * @param paramName 参数名称
   * @param value 参数值（可能是字面常量或引用）
   * @param isConstant 是否是常量值（非引用）
   * @return 绑定可捕获性分类
   */
  static BindingCapturability evaluateCapturability(
      String paramName, String value, boolean isConstant) {

    // 疑似秘密参数名称 -> 永不捕获
    if (SUSPECTED_SECRET_PARAM_NAMES.contains(paramName.toLowerCase())) {
      return BindingCapturability.NEVER_CAPTURE;
    }

    if (isConstant) {
      // 常量值：检查是否在白名单中
      if (ALLOWED_CONSTANTS.contains(value)) {
        return BindingCapturability.CONSTANT_ALLOWED;
      }
      // 未知常量 -> 默认不捕获（可能是秘密或环境特定值）
      return BindingCapturability.NEVER_CAPTURE;
    }

    // 非常量（引用）-> 安全捕获
    // INPUT 引用、PREVIOUS_STEP_OUTPUT 引用都是结构化引用，不包含实际值
    return BindingCapturability.SAFE_TO_CAPTURE;
  }

  /**
   * 检查参数值是否看起来像引用而不是字面值。
   *
   * <p>V1 简化检测：检查常见引用模式。
   *
   * @param value 参数值
   * @return true 如果看起来像引用
   */
  static boolean looksLikeReference(String value) {
    if (value == null || value.isBlank()) {
      return false;
    }

    // 检查常见引用模式
    // INPUT 引用: "serviceName" (简单字段名)
    // PREVIOUS_STEP_OUTPUT 引用: "0.serviceId" (步骤索引.输出名)
    // RUNTIME_CONTEXT 引用: "environment"

    // 如果包含点且前面是数字 -> 可能是 PREVIOUS_STEP_OUTPUT
    if (value.matches("\\d+\\..+")) {
      return true;
    }

    // 简单标识符（字母开头，仅字母数字下划线）-> 可能是引用
    if (value.matches("[a-zA-Z][a-zA-Z0-9_]*")) {
      return true;
    }

    // 其他 -> 视为字面值
    return false;
  }
}
