package cn.bitcss.arctra.runtime;

import cn.bitcss.arctra.agent.AgentDefinition;
import cn.bitcss.arctra.agent.AgentRequest;
import cn.bitcss.arctra.agent.AgentResult;

/**
 * Agent runtime contract.
 *
 * <p>Internal kernel contract for executing agents. This is intentionally package-private; whether
 * it becomes a public extension SPI will be determined by real runtime and engine usage scenarios.
 *
 * @author lov3r
 */
interface AgentRuntime {

  /**
   * Execute an agent with the given definition and request.
   *
   * @param definition the agent definition
   * @param request the user request
   * @return the execution result
   */
  AgentResult execute(AgentDefinition definition, AgentRequest request);
}
