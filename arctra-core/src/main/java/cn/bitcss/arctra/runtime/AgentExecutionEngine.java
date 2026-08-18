package cn.bitcss.arctra.runtime;

import cn.bitcss.arctra.agent.AgentDefinition;
import cn.bitcss.arctra.agent.AgentExecutionContext;
import cn.bitcss.arctra.agent.AgentRequest;
import cn.bitcss.arctra.agent.AgentResult;

/**
 * Agent execution engine contract.
 *
 * <p>Defines the extension contract for pluggable execution engines. An engine is responsible for
 * executing an agent with a given definition, request, and execution context, producing a result.
 *
 * <p>This is a public extension contract that allows different execution strategies to be
 * implemented and composed with the Arctra runtime.
 *
 * <p><strong>Evolution in M2:</strong> Added execution context support for multi-turn conversation
 * continuity. Engines should implement the 3-parameter {@link #execute(AgentDefinition,
 * AgentRequest, AgentExecutionContext)} method as the canonical execution contract.
 *
 * @author lov3r
 */
public interface AgentExecutionEngine {

  /**
   * Execute an agent with execution context.
   *
   * <p>This is the canonical execution method introduced in M2. Engines should implement this
   * method and handle the execution context appropriately (e.g., using {@code context.sessionId()}
   * for multi-turn conversation continuity).
   *
   * <p>Engines that do not support session continuity may ignore the context or throw {@link
   * UnsupportedOperationException} when {@code context.sessionId()} is not null.
   *
   * @param definition the agent definition
   * @param request the user request
   * @param context execution context (session, etc.)
   * @return the execution result
   */
  AgentResult execute(
      AgentDefinition definition, AgentRequest request, AgentExecutionContext context);

  /**
   * Execute an agent without execution context (stateless).
   *
   * <p>Convenience method for stateless execution. Delegates to {@link
   * #execute(AgentDefinition, AgentRequest, AgentExecutionContext)} with a stateless context.
   *
   * <p>This method maintains backward compatibility with M1 code.
   *
   * @param definition the agent definition
   * @param request the user request
   * @return the execution result
   */
  default AgentResult execute(AgentDefinition definition, AgentRequest request) {
    return execute(definition, request, AgentExecutionContext.stateless());
  }
}
