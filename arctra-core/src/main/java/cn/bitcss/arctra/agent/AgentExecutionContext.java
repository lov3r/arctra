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
