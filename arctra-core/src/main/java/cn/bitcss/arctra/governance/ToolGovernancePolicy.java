package cn.bitcss.arctra.governance;

import cn.bitcss.arctra.agent.AgentExecutionContext;

/**
 * Policy for governing tool invocations.
 *
 * <p>ToolGovernancePolicy evaluates whether a tool invocation should be allowed, denied, or require
 * approval.
 *
 * <h2>Framework-Neutral</h2>
 *
 * <p>This interface remains framework-neutral. It does not depend on Spring AI or any specific tool
 * framework. Tool metadata is passed as simple strings.
 *
 * <h2>Governance vs Process</h2>
 *
 * <p><b>Governance:</b> Answers "May this tool execute?"
 *
 * <p><b>Process:</b> Answers "What is the task execution lifecycle?"
 *
 * <p>Governance is orthogonal to Process. REQUIRE_APPROVAL is a suspension trigger, but Governance
 * itself does not manage process lifecycle.
 *
 * <h2>Usage</h2>
 *
 * <pre>{@code
 * ToolGovernancePolicy policy = ...;
 * GovernanceDecision decision = policy.evaluate(
 *     "rollbackDeployment",
 *     "{\"version\":\"1.2.2\"}",
 *     context
 * );
 * }</pre>
 *
 * @author lov3r
 * @since M4
 */
@FunctionalInterface
public interface ToolGovernancePolicy {

  /**
   * Evaluate whether a tool invocation should execute.
   *
   * @param toolName tool name (e.g., "queryLogs", "rollbackDeployment")
   * @param toolArguments tool arguments (JSON string)
   * @param context agent execution context
   * @return governance decision (ALLOW, DENY, or REQUIRE_APPROVAL)
   */
  GovernanceDecision evaluate(
      String toolName, String toolArguments, AgentExecutionContext context);

  /**
   * Permissive policy that allows all tool invocations.
   *
   * <p>Use for development/testing or when no governance is needed.
   *
   * @return policy that always returns ALLOW
   */
  static ToolGovernancePolicy allowAll() {
    return (toolName, toolArguments, context) -> GovernanceDecision.ALLOW;
  }
}
