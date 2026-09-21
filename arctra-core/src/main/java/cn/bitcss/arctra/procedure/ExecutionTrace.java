package cn.bitcss.arctra.procedure;

import java.util.List;
import java.util.Map;

/**
 * 执行跟踪信息，用于候选过程提取。
 *
 * <p><strong>M8-B V1：</strong>从成功执行中直接捕获的工具调用序列。不通过 ExecutionLedger，而是在执行期间直接收集。
 *
 * <p>这是过渡性内部表示，仅用于候选提取。不是持久化模型。
 *
 * @param agentName 代理名称（用于作用域）
 * @param intentKey 意图键（从请求推导或显式提供）
 * @param toolInvocations 工具调用序列（按执行顺序）
 * @author lov3r
 * @since M8-B
 */
record ExecutionTrace(String agentName, String intentKey, List<ToolInvocation> toolInvocations) {

  /**
   * 单个工具调用记录。
   *
   * @param toolName 工具名称
   * @param inputSchemaHash 输入模式哈希（用于兼容性检查）
   * @param arguments 工具参数（JSON 字符串）
   * @param result 工具结果（JSON 字符串）
   */
  record ToolInvocation(
      String toolName, String inputSchemaHash, String arguments, String result) {}
}
