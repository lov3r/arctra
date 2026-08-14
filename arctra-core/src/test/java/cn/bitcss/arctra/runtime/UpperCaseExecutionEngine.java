package cn.bitcss.arctra.runtime;

import cn.bitcss.arctra.agent.AgentDefinition;
import cn.bitcss.arctra.agent.AgentRequest;
import cn.bitcss.arctra.agent.AgentResult;

/**
 * UpperCase execution engine for testing engine replaceability.
 *
 * <p>Converts the user message to uppercase as the result.
 *
 * @author lov3r
 */
class UpperCaseExecutionEngine implements AgentExecutionEngine {

  @Override
  public AgentResult execute(AgentDefinition definition, AgentRequest request) {
    return new AgentResult(request.userMessage().toUpperCase());
  }
}
