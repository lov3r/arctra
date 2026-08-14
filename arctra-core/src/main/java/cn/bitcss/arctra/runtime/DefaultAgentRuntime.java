package cn.bitcss.arctra.runtime;

import cn.bitcss.arctra.agent.AgentDefinition;
import cn.bitcss.arctra.agent.AgentRequest;
import cn.bitcss.arctra.agent.AgentResult;

/**
 * Default implementation of {@link AgentRuntime}.
 *
 * <p>Delegates execution to the configured {@link AgentExecutionEngine}.
 *
 * @author lov3r
 */
class DefaultAgentRuntime implements AgentRuntime {

  private final AgentExecutionEngine engine;

  DefaultAgentRuntime(AgentExecutionEngine engine) {
    if (engine == null) {
      throw new IllegalArgumentException("engine cannot be null");
    }
    this.engine = engine;
  }

  @Override
  public AgentResult execute(AgentDefinition definition, AgentRequest request) {
    return engine.execute(definition, request);
  }
}
