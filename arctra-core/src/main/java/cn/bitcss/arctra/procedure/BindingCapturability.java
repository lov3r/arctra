package cn.bitcss.arctra.procedure;

/**
 * 绑定可捕获性分类。
 *
 * <p><strong>M8-B V1 安全方法：</strong>白名单优于黑名单。仅捕获显式合格的绑定。
 *
 * <p>默认为 NEVER_CAPTURE，只有明确安全的绑定才标记为可捕获。
 *
 * @author lov3r
 * @since M8-B
 */
enum BindingCapturability {

  /** 安全捕获 - INPUT 引用、PREVIOUS_STEP_OUTPUT 引用。 */
  SAFE_TO_CAPTURE,

  /** 常量允许 - 显式白名单的常量值。 */
  CONSTANT_ALLOWED,

  /** 永不捕获 - 默认策略，包括疑似秘密、动态值等。 */
  NEVER_CAPTURE
}
