package cn.bitcss.arctra.procedure;

import cn.bitcss.arctra.governance.GovernanceDecision;
import cn.bitcss.arctra.governance.ToolGovernancePolicy;
import java.util.Objects;

/**
 * 操作治理评估器（用于缓存过程步骤）。
 *
 * <p><strong>M8-C 治理不变式：</strong>缓存过程步骤必须通过当前治理策略重新评估。
 *
 * <p>这是内部包私有组件，不是公共 API。确保 HITL 边界保持正确。
 *
 * <p><strong>关键语义：</strong>
 *
 * <ul>
 *   <li>治理是当前决策，不是历史决策
 *   <li>缓存执行永不绕过 REQUIRE_APPROVAL
 *   <li>策略改变可能使以前允许的操作现在需要批准
 *   <li>DENY 决策阻止缓存步骤执行（过程失效）
 * </ul>
 *
 * <p><strong>与 GovernanceToolCallingAdvisor 的区别：</strong>
 *
 * <ul>
 *   <li>GovernanceToolCallingAdvisor：评估 Spring AI ToolCall（模型生成的调用）
 *   <li>OperationGovernanceEvaluator：评估过程步骤（从可重用过程执行）
 * </ul>
 *
 * <p>两者都使用相同的 ToolGovernancePolicy，但在不同执行路径中。
 *
 * @author lov3r
 * @since M8-C
 */
class OperationGovernanceEvaluator {

  private final ToolGovernancePolicy policy;

  OperationGovernanceEvaluator(ToolGovernancePolicy policy) {
    this.policy = Objects.requireNonNull(policy, "policy cannot be null");
  }

  /**
   * 评估过程步骤的治理决策。
   *
   * <p>使用当前治理策略评估步骤，而不是过程创建时的历史策略。
   *
   * @param step 要评估的过程步骤
   * @param resolvedArguments 已解析的参数（JSON 字符串）
   * @return 治理决策
   */
  GovernanceDecision evaluate(ProcedureStep step, String resolvedArguments) {
    Objects.requireNonNull(step, "step cannot be null");
    Objects.requireNonNull(resolvedArguments, "resolvedArguments cannot be null");

    // 使用当前策略评估
    // 上下文在 V1 中为 null（未来可能需要执行上下文）
    return policy.evaluate(step.toolName(), resolvedArguments, null);
  }

  /**
   * 检查决策是否允许立即执行。
   *
   * @param decision 治理决策
   * @return true 如果可以立即执行（ALLOW）
   */
  static boolean canExecuteImmediately(GovernanceDecision decision) {
    return decision == GovernanceDecision.ALLOW;
  }

  /**
   * 检查决策是否需要批准。
   *
   * @param decision 治理决策
   * @return true 如果需要人工批准
   */
  static boolean requiresApproval(GovernanceDecision decision) {
    return decision == GovernanceDecision.REQUIRE_APPROVAL;
  }

  /**
   * 检查决策是否拒绝执行。
   *
   * @param decision 治理决策
   * @return true 如果被策略拒绝
   */
  static boolean isDenied(GovernanceDecision decision) {
    return decision == GovernanceDecision.DENY;
  }
}
