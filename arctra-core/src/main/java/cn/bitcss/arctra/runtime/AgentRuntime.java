package cn.bitcss.arctra.runtime;

import cn.bitcss.arctra.agent.Agent;
import cn.bitcss.arctra.agent.AgentDefinition;
import cn.bitcss.arctra.agent.AgentExecutionContext;
import cn.bitcss.arctra.agent.AgentRequest;
import cn.bitcss.arctra.agent.AgentResult;

/**
 * Agent runtime for creating agent handles and executing agents.
 *
 * <p>AgentRuntime is the primary entry point for agent execution. It provides:
 *
 * <ul>
 *   <li>Agent handle creation (recommended user path)
 *   <li>Direct execution (low-level path)
 * </ul>
 *
 * <h2>Recommended User Path</h2>
 *
 * <p>Create an Agent handle and invoke through it:
 *
 * <pre>{@code
 * // Composition root (e.g., Spring configuration)
 * AgentRuntime runtime = ...;
 * Agent agent = runtime.agent(
 *     new AgentDefinition("name", "description")
 * );
 *
 * // Business code
 * AgentResult result = agent.execute(request);
 * }</pre>
 *
 * <h2>Low-Level Path</h2>
 *
 * <p>For advanced use cases, direct execution is available:
 *
 * <pre>{@code
 * runtime.execute(definition, request, context);
 * }</pre>
 *
 * <h2>Current Responsibilities (M3)</h2>
 * <ul>
 *   <li>Create Agent handles bound to AgentDefinition
 *   <li>Delegate execution to AgentExecutionEngine
 * </ul>
 *
 * <h2>Future Compatibility</h2>
 *
 * <p>Future versions may route execution through process runtimes for complex scenarios, but the
 * Agent API will remain unchanged.
 *
 * @see Agent
 * @see AgentExecutionEngine
 * @author lov3r
 */
public interface AgentRuntime {

  /**
   * Create an agent invocation handle.
   *
   * <p>Returns a stateless, reusable Agent handle bound to the given definition. The handle can be
   * invoked multiple times and is safe for concurrent use.
   *
   * <p>Multiple calls with the same definition may return different instances, but all behave
   * identically.
   *
   * <h3>Usage</h3>
   *
   * <pre>{@code
   * Agent agent = runtime.agent(
   *     new AgentDefinition("Incident Investigator", "You are...")
   * );
   *
   * // Reusable
   * agent.execute(request1);
   * agent.execute(request2, context);
   * }</pre>
   *
   * @param definition agent definition (name and description)
   * @return agent invocation handle
   * @throws NullPointerException if definition is null
   */
  Agent agent(AgentDefinition definition);

  /**
   * Execute agent directly (low-level API).
   *
   * <p>This is the canonical execution method. For most use cases, prefer creating an Agent handle
   * via {@link #agent(AgentDefinition)} and invoking through the handle.
   *
   * <p>Direct execution is useful for:
   *
   * <ul>
   *   <li>One-off executions without handle creation overhead
   *   <li>Dynamic agent definitions
   *   <li>Testing and debugging
   * </ul>
   *
   * @param definition agent definition
   * @param request user request
   * @param context execution context (session, etc.)
   * @return execution result
   * @throws NullPointerException if any parameter is null
   */
  AgentResult execute(
      AgentDefinition definition, AgentRequest request, AgentExecutionContext context);

  /**
   * Execute agent with stateless context (convenience).
   *
   * <p>Equivalent to:
   *
   * <pre>{@code
   * execute(definition, request, AgentExecutionContext.stateless())
   * }</pre>
   *
   * @param definition agent definition
   * @param request user request
   * @return execution result
   * @throws NullPointerException if definition or request is null
   */
  default AgentResult execute(AgentDefinition definition, AgentRequest request) {
    return execute(definition, request, AgentExecutionContext.stateless());
  }
}
