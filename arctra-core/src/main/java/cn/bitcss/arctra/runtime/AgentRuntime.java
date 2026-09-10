package cn.bitcss.arctra.runtime;

import cn.bitcss.arctra.agent.Agent;
import cn.bitcss.arctra.agent.AgentDefinition;
import cn.bitcss.arctra.agent.AgentExecutionContext;
import cn.bitcss.arctra.agent.AgentRequest;
import cn.bitcss.arctra.agent.AgentResult;
import cn.bitcss.arctra.process.ContinuationSignal;

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

  /**
   * Resume durable suspended process (M5 cross-runtime recovery).
   *
   * <p>Entry point for resuming checkpoint-backed durable suspended processes across runtime/JVM
   * boundaries. Caller does NOT need to hold the original Java {@link
   * cn.bitcss.arctra.process.AgentProcess} handle - recovery is based purely on durable checkpoint
   * state.
   *
   * <p><strong>Requires durable-capable engine:</strong> The underlying {@link
   * AgentExecutionEngine} must implement {@link DurableExecutionEngine}. If not, this method throws
   * {@link UnsupportedOperationException}.
   *
   * <p><strong>processId semantics:</strong> Stable logical process identifier that remains
   * constant across the entire execution lifecycle. Generated during initial durable suspension and
   * persisted in checkpoint.
   *
   * <p><strong>checkpointVersion semantics:</strong> Suspension episode version serving as fencing
   * token for optimistic concurrency control. Increments on each re-suspension (v1, v2, v3, ...).
   * Used by durable engine for CHECK A/B validation.
   *
   * <p><strong>This method is pure delegation:</strong> Does NOT perform checkpoint loading,
   * version validation, binding resolution, or orchestration logic. All durable recovery pipeline
   * logic lives in {@link DurableExecutionEngine#resumeProcess}.
   *
   * @param processId stable process identifier (from checkpoint)
   * @param checkpointVersion suspension episode version (fencing token)
   * @param signal continuation signal (approval/rejection decision)
   * @return execution result (may be suspended again with incremented version)
   * @throws UnsupportedOperationException if engine does not implement {@link
   *     DurableExecutionEngine}
   * @throws NullPointerException if processId or signal is null
   * @since M5-T4
   */
  AgentResult resumeProcess(
      String processId, long checkpointVersion, ContinuationSignal signal);
}
