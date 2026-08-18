package cn.bitcss.arctra.runtime;

import cn.bitcss.arctra.agent.Agent;
import cn.bitcss.arctra.agent.AgentDefinition;
import cn.bitcss.arctra.agent.AgentExecutionContext;
import cn.bitcss.arctra.agent.AgentRequest;
import cn.bitcss.arctra.agent.AgentResult;
import java.util.Objects;

/**
 * Default agent handle implementation.
 *
 * <p>DefaultAgent is a stateless invocation handle that binds an AgentDefinition with an
 * AgentRuntime for execution.
 *
 * <p>This class is package-private and should not be used directly. Obtain Agent instances via
 * {@link AgentRuntime#agent(AgentDefinition)}.
 *
 * <h2>Design</h2>
 *
 * <p>DefaultAgent delegates execution to AgentRuntime, which allows future runtime evolution
 * (e.g., process runtime insertion) without changing Agent API.
 *
 * <pre>
 * Agent.execute()
 *     ↓
 * AgentRuntime.execute()
 *     ↓
 * [Future: Process Runtime]
 *     ↓
 * AgentExecutionEngine
 * </pre>
 *
 * @author lov3r
 */
final class DefaultAgent implements Agent {

  private final AgentDefinition definition;
  private final AgentRuntime runtime;

  /**
   * Create default agent handle.
   *
   * @param definition agent definition
   * @param runtime agent runtime for execution delegation
   */
  DefaultAgent(AgentDefinition definition, AgentRuntime runtime) {
    this.definition = Objects.requireNonNull(definition, "definition cannot be null");
    this.runtime = Objects.requireNonNull(runtime, "runtime cannot be null");
  }

  @Override
  public AgentResult execute(AgentRequest request, AgentExecutionContext context) {
    Objects.requireNonNull(request, "request cannot be null");
    Objects.requireNonNull(context, "context cannot be null");

    return runtime.execute(definition, request, context);
  }
}
