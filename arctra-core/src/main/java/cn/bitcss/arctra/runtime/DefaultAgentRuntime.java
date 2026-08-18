package cn.bitcss.arctra.runtime;

import cn.bitcss.arctra.agent.Agent;
import cn.bitcss.arctra.agent.AgentDefinition;
import cn.bitcss.arctra.agent.AgentExecutionContext;
import cn.bitcss.arctra.agent.AgentRequest;
import cn.bitcss.arctra.agent.AgentResult;
import java.util.Objects;

/**
 * Default AgentRuntime implementation.
 *
 * <p>DefaultAgentRuntime provides a minimal runtime that:
 *
 * <ul>
 *   <li>Creates Agent handles bound to AgentDefinition
 *   <li>Delegates execution to AgentExecutionEngine
 * </ul>
 *
 * <h2>Usage</h2>
 *
 * <pre>{@code
 * // Create runtime with engine
 * AgentExecutionEngine engine = new SpringAiToolCallingEngine(...);
 * AgentRuntime runtime = new DefaultAgentRuntime(engine);
 *
 * // Create agent handle
 * Agent agent = runtime.agent(
 *     new AgentDefinition("name", "description")
 * );
 *
 * // Execute
 * agent.execute(request, context);
 * }</pre>
 *
 * @author lov3r
 */
public class DefaultAgentRuntime implements AgentRuntime {

  private final AgentExecutionEngine engine;

  /**
   * Create default agent runtime.
   *
   * @param engine agent execution engine
   * @throws NullPointerException if engine is null
   */
  public DefaultAgentRuntime(AgentExecutionEngine engine) {
    this.engine = Objects.requireNonNull(engine, "engine cannot be null");
  }

  @Override
  public Agent agent(AgentDefinition definition) {
    Objects.requireNonNull(definition, "definition cannot be null");
    return new DefaultAgent(definition, this);
  }

  @Override
  public AgentResult execute(
      AgentDefinition definition, AgentRequest request, AgentExecutionContext context) {
    Objects.requireNonNull(definition, "definition cannot be null");
    Objects.requireNonNull(request, "request cannot be null");
    Objects.requireNonNull(context, "context cannot be null");

    return engine.execute(definition, request, context);
  }
}
