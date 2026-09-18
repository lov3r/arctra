package cn.bitcss.arctra.agent;

import cn.bitcss.arctra.durability.DurabilityMode;

/**
 * Agent execution context.
 *
 * <p>Represents execution-level environment for an agent invocation, independent of user input
 * ({@link AgentRequest}) and agent template ({@link AgentDefinition}).
 *
 * <h2>M2 Multi-Turn Support</h2>
 *
 * <p>Session identity enables conversation continuity across multiple turns. Same sessionId →
 * conversation history preserved. Different sessionId → isolated conversations.
 *
 * <h2>M6-T6.4 Durability Support</h2>
 *
 * <p>Durability mode determines whether accepted semantic execution progress survives process/JVM
 * failures:
 *
 * <ul>
 *   <li><strong>EPHEMERAL</strong> (default): lightweight fast path, no crash recovery
 *   <li><strong>DURABLE</strong>: durable continuation materialization, cross-JVM recovery
 * </ul>
 *
 * <p>Durability is independent from governance (ALLOW/REQUIRE_APPROVAL) and session state.
 *
 * <h2>Usage</h2>
 *
 * <pre>{@code
 * // M1: Stateless ephemeral (default, backward compatible)
 * var context = AgentExecutionContext.stateless();
 *
 * // M2: Session-based ephemeral (default)
 * var context = AgentExecutionContext.withSession("session-123");
 *
 * // M6-T6.4: Durable session-based
 * var context = AgentExecutionContext.durableSession("session-123");
 *
 * // M6-T6.4: Durable stateless
 * var context = AgentExecutionContext.durableStateless();
 * }</pre>
 *
 * @param sessionId optional session identifier for conversation continuity (null for stateless)
 * @param durability execution durability mode (non-null)
 * @author lov3r
 * @since M2-T2
 * @since M6-T6.4 Durability support
 */
public record AgentExecutionContext(String sessionId, DurabilityMode durability) {

  /**
   * Compact constructor with validation.
   *
   * @throws IllegalArgumentException if durability is null
   */
  public AgentExecutionContext {
    if (durability == null) {
      throw new IllegalArgumentException("durability cannot be null");
    }
  }

  /**
   * Create stateless ephemeral execution context (M1 backward compatible).
   *
   * @return stateless ephemeral context
   */
  public static AgentExecutionContext stateless() {
    return new AgentExecutionContext(null, DurabilityMode.EPHEMERAL);
  }

  /**
   * Create session-aware ephemeral execution context (M2 backward compatible default).
   *
   * @param sessionId session identifier, must not be null or blank
   * @return session-aware ephemeral context
   * @throws IllegalArgumentException if sessionId is null or blank
   */
  public static AgentExecutionContext withSession(String sessionId) {
    if (sessionId == null || sessionId.isBlank()) {
      throw new IllegalArgumentException("sessionId cannot be null or blank");
    }
    return new AgentExecutionContext(sessionId, DurabilityMode.EPHEMERAL);
  }

  /**
   * Create session-aware durable execution context.
   *
   * <p>Durable execution materializes durable continuation checkpoints for cross-JVM recovery.
   * Accepted semantic progress (logical operations, tool results) survives process crashes.
   *
   * @param sessionId session identifier (may be null for stateless durable execution)
   * @return session-aware durable context
   * @throws IllegalArgumentException if sessionId is blank
   * @since M6-T6.4
   */
  public static AgentExecutionContext durableSession(String sessionId) {
    if (sessionId != null && sessionId.isBlank()) {
      throw new IllegalArgumentException("sessionId cannot be blank");
    }
    return new AgentExecutionContext(sessionId, DurabilityMode.DURABLE);
  }

  /**
   * Create stateless durable execution context.
   *
   * @return stateless durable context
   * @since M6-T6.4
   */
  public static AgentExecutionContext durableStateless() {
    return new AgentExecutionContext(null, DurabilityMode.DURABLE);
  }
}
