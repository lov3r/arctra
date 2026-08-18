package cn.bitcss.arctra.runtime;

import cn.bitcss.arctra.agent.AgentDefinition;
import cn.bitcss.arctra.agent.AgentExecutionContext;
import cn.bitcss.arctra.agent.AgentRequest;
import cn.bitcss.arctra.agent.AgentResult;

/**
 * Echo execution engine for testing engine replaceability.
 *
 * <p>Simply echoes the user message back as the result.
 *
 * @author lov3r
 */
class EchoExecutionEngine implements AgentExecutionEngine {

  @Override
  public AgentResult execute(
      AgentDefinition definition, AgentRequest request, AgentExecutionContext context) {
    return new AgentResult("Echo: " + request.userMessage());
  }
}
