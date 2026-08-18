package cn.bitcss.arctra.agent;

import java.util.Objects;

/**
 * Agent invocation handle.
 *
 * <p>Agent is a stateless invocation handle for executing a bound {@link AgentDefinition}. It
 * provides a framework-neutral execution API and delegates execution semantics to {@link
 * cn.bitcss.arctra.runtime.AgentRuntime}.
 *
 * <h2>Characteristics</h2>
 * <ul>
 *   <li><b>Stateless:</b> Does not own conversation, session, or process state
 *   <li><b>Reusable:</b> Can be invoked multiple times safely
 *   <li><b>Protocol:</b> Defines invocation contract, not implementation
 *   <li><b>Bound:</b> Tied to a specific AgentDefinition for execution
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Create agent handle (composition root)
 * Agent agent = runtime.agent(
 *     new AgentDefinition("name", "description")
 * );
 *
 * // Stateless invocation
 * AgentResult result = agent.execute(
 *     new AgentRequest("Analyze incident")
 * );
 *
 * // Stateful invocation (with session)
 * AgentResult result = agent.execute(
 *     new AgentRequest("Continue analysis"),
 *     AgentExecutionContext.withSession("incident-123")
 * );
 * }</pre>
 *
 * <h2>State Ownership</h2>
 * <ul>
 *   <li>Conversation state → {@link cn.bitcss.arctra.runtime.AgentRuntime} / ChatMemory
 *   <li>Execution state → per-execution (AgentResult)
 *   <li>Agent handle → stateless (no mutable state)
 * </ul>
 *
 * @see cn.bitcss.arctra.runtime.AgentRuntime
 * @see AgentDefinition
 * @see AgentRequest
 * @see AgentExecutionContext
 * @author lov3r
 */
public interface Agent {

  /**
   * Execute agent with stateless context.
   *
   * <p>This is a convenience method equivalent to:
   *
   * <pre>{@code
   * execute(request, AgentExecutionContext.stateless())
   * }</pre>
   *
   * @param request user request
   * @return execution result with content and evidence
   * @throws NullPointerException if request is null
   */
  default AgentResult execute(AgentRequest request) {
    Objects.requireNonNull(request, "request cannot be null");
    return execute(request, AgentExecutionContext.stateless());
  }

  /**
   * Execute agent with execution context (canonical method).
   *
   * <p>This is the canonical execution method. All execution flows through this method.
   *
   * <p>The execution context provides session identity for conversation continuity. Multiple calls
   * with the same sessionId will maintain conversation history.
   *
   * <h3>Stateless Execution</h3>
   *
   * <pre>{@code
   * agent.execute(request, AgentExecutionContext.stateless())
   * }</pre>
   *
   * <h3>Stateful Execution</h3>
   *
   * <pre>{@code
   * agent.execute(request, AgentExecutionContext.withSession("session-id"))
   * }</pre>
   *
   * @param request user request
   * @param context execution context (session, etc.)
   * @return execution result with content and evidence
   * @throws NullPointerException if request or context is null
   */
  AgentResult execute(AgentRequest request, AgentExecutionContext context);
}
