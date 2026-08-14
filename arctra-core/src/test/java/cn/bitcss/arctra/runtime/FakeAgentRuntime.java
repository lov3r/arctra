package cn.bitcss.arctra.runtime;

import cn.bitcss.arctra.agent.AgentDefinition;
import cn.bitcss.arctra.agent.AgentRequest;
import cn.bitcss.arctra.agent.AgentResult;

/**
 * Fake agent runtime for testing the runtime contract.
 *
 * <p>Returns a simple fake response without invoking any real model or engine.
 *
 * @author lov3r
 */
class FakeAgentRuntime implements AgentRuntime {

  @Override
  public AgentResult execute(AgentDefinition definition, AgentRequest request) {
    return new AgentResult("Fake response to: " + request.userMessage());
  }
}
