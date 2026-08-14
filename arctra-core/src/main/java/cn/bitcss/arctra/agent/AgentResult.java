package cn.bitcss.arctra.agent;

/**
 * The result of an agent execution.
 *
 * <p>Represents the outcome produced by the runtime, not just a transport-layer response.
 *
 * @author lov3r
 */
public record AgentResult(String content) {

  public AgentResult {
    if (content == null) {
      throw new IllegalArgumentException("content cannot be null");
    }
  }
}
