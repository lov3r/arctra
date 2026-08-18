package cn.bitcss.arctra.runtime;

import cn.bitcss.arctra.agent.AgentDefinition;
import cn.bitcss.arctra.agent.AgentExecutionContext;
import cn.bitcss.arctra.agent.AgentRequest;
import cn.bitcss.arctra.agent.AgentResult;

/**
 * Fake execution engine for testing the execution engine contract.
 *
 * <p>Returns a simple fake response without any real execution logic.
 *
 * @author lov3r
 */
class FakeExecutionEngine implements AgentExecutionEngine {

  @Override
  public AgentResult execute(
      AgentDefinition definition, AgentRequest request, AgentExecutionContext context) {
    return new AgentResult("Fake response to: " + request.userMessage());
  }
}
