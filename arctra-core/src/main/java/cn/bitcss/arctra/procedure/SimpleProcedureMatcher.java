package cn.bitcss.arctra.procedure;

import java.util.Objects;
import java.util.Optional;

/**
 * 简单过程匹配器（M8 集成 V1）。
 *
 * <p><strong>V1 简化：</strong>基于 agentName 简单匹配，不做复杂意图解析。
 *
 * <p><strong>匹配逻辑：</strong>
 *
 * <ol>
 *   <li>查找 scope = forAgent(agentName) 且 intentKey = agentName 的过程
 *   <li>过滤 status = VALID 的修订
 *   <li>返回最新（最高 revision）的 VALID 过程
 * </ol>
 *
 * <p><strong>延迟到 M8-E：</strong>
 *
 * <ul>
 *   <li>复杂意图解析（语义匹配、模糊匹配）
 *   <li>多候选排序（相似度评分）
 *   <li>上下文感知匹配
 * </ul>
 *
 * @author lov3r
 * @since M8-Integration
 */
public class SimpleProcedureMatcher {

  private final ReusableProcedureStore store;

  public SimpleProcedureMatcher(ReusableProcedureStore store) {
    this.store = Objects.requireNonNull(store, "store cannot be null");
  }

  /**
   * 查找匹配的过程。
   *
   * <p>V1 简化：基于 agentName 简单匹配。
   *
   * @param agentName agent 名称
   * @param userPrompt 用户提示（V1 未使用，保留用于 M8-E）
   * @return 匹配的过程（最新 VALID 修订），如果没有匹配则返回空
   */
  public Optional<ReusableProcedure> findMatch(String agentName, String userPrompt) {
    Objects.requireNonNull(agentName, "agentName cannot be null");
    Objects.requireNonNull(userPrompt, "userPrompt cannot be null");

    // V1 简化：intentKey = agentName
    String intentKey = agentName;
    ProcedureScope scope = ProcedureScope.forAgent(agentName);

    // 查找所有匹配的过程（所有修订）
    var procedures = store.findByIntent(scope, intentKey);

    // 过滤 VALID 状态，返回最新修订
    return procedures.stream()
        .filter(p -> p.status() == ProcedureStatus.VALID)
        .max((a, b) -> Integer.compare(a.revision(), b.revision()));
  }
}
