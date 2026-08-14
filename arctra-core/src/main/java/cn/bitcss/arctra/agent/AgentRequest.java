package cn.bitcss.arctra.agent;

/**
 * A request to execute an agent with a user message.
 *
 * <p>This is a stateless, single-turn request. Session management is not part of this model.
 *
 * @author lov3r
 */
public record AgentRequest(String userMessage) {

  public AgentRequest {
    if (userMessage == null || userMessage.isBlank()) {
      throw new IllegalArgumentException("userMessage cannot be blank");
    }
  }
}
