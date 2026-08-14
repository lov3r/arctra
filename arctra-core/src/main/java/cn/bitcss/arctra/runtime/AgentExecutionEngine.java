package cn.bitcss.arctra.runtime;

import cn.bitcss.arctra.agent.AgentDefinition;
import cn.bitcss.arctra.agent.AgentRequest;
import cn.bitcss.arctra.agent.AgentResult;

/**
 * Agent execution engine contract.
 *
 * <p>Defines the extension contract for pluggable execution engines. An engine is responsible for
 * executing an agent with a given definition and request, and producing a result.
 *
 * <p>This is a public extension contract that allows different execution strategies to be
 * implemented and composed with the Arctra runtime.
 *
 * @author lov3r
 */
public interface AgentExecutionEngine {

  /**
   * Execute an agent.
   *
   * @param definition the agent definition
   * @param request the user request
   * @return the execution result
   */
  AgentResult execute(AgentDefinition definition, AgentRequest request);
}
