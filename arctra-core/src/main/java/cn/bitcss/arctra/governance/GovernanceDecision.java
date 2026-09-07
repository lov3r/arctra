package cn.bitcss.arctra.governance;

/**
 * Governance decision for tool invocation.
 *
 * <p>Represents the policy decision on whether a tool invocation may execute.
 *
 * <h2>Decision Types</h2>
 *
 * <ul>
 *   <li><b>ALLOW:</b> Tool may execute immediately
 *   <li><b>DENY:</b> Tool must not execute (policy violation)
 *   <li><b>REQUIRE_APPROVAL:</b> Tool requires human approval before execution
 * </ul>
 *
 * <h2>Usage</h2>
 *
 * <pre>{@code
 * GovernanceDecision decision = policy.evaluate("rollbackDeployment", args, context);
 * switch (decision) {
 *     case ALLOW -> executeTool();
 *     case DENY -> rejectTool();
 *     case REQUIRE_APPROVAL -> suspendForApproval();
 * }
 * }</pre>
 *
 * @author lov3r
 * @since M4
 */
public enum GovernanceDecision {

  /**
   * Tool may execute.
   *
   * <p>Policy allows this tool invocation to proceed immediately.
   */
  ALLOW,

  /**
   * Tool must not execute.
   *
   * <p>Policy denies this tool invocation (e.g., insufficient permission, safety violation).
   */
  DENY,

  /**
   * Tool requires approval.
   *
   * <p>Policy requires human approval before this tool can execute (e.g., high-risk operation).
   */
  REQUIRE_APPROVAL
}
