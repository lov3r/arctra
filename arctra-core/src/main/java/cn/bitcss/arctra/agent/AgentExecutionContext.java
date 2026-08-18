package cn.bitcss.arctra.agent;

/**
 * Agent execution context.
 *
 * <p>Represents execution-level environment for an agent invocation, independent of user input
 * ({@link AgentRequest}) and agent template ({@link AgentDefinition}).
 *
 * <p>Currently contains session identity for multi-turn conversation continuity. This is an
 * execution-level concern, not a request-level concern (user input) or definition-level concern
 * (agent template).
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Stateless execution (M1 behavior)
 * var context = AgentExecutionContext.stateless();
 * engine.execute(definition, request, context);
 *
 * // Session-based execution (M2 multi-turn)
 * var context = AgentExecutionContext.withSession("session-123");
 * engine.execute(definition, request, context);  // Turn 1
 * engine.execute(definition, followUp, context);  // Turn 2 - continues conversation
 * }</pre>
 *
 * <h2>Session Semantics</h2>
 * <ul>
 *   <li>Same sessionId → conversation continuity (Turn 2 sees Turn 1 context)</li>
 *   <li>Different sessionId → conversation isolation (independent histories)</li>
 *   <li>null sessionId → stateless execution (no conversation history)</li>
 * </ul>
 *
 * <h2>Design Note</h2>
 * <p>This is an execution-level semantic, not a request-level parameter. Session identity is
 * orthogonal to user input — the same user input can be executed in different sessions or
 * statelessly.
 *
 * @param sessionId optional session identifier for conversation continuity. {@code null} indicates
 *     stateless execution.
 * @author lov3r
 */
public record AgentExecutionContext(String sessionId) {

  /**
   * Create a stateless execution context (no session).
   *
   * @return stateless context with {@code sessionId = null}
   */
  public static AgentExecutionContext stateless() {
    return new AgentExecutionContext(null);
  }

  /**
   * Create an execution context with session.
   *
   * @param sessionId session identifier, must not be null or blank
   * @return context with the given sessionId
   * @throws IllegalArgumentException if sessionId is null or blank
   */
  public static AgentExecutionContext withSession(String sessionId) {
    if (sessionId == null || sessionId.isBlank()) {
      throw new IllegalArgumentException("sessionId cannot be null or blank");
    }
    return new AgentExecutionContext(sessionId);
  }
}
