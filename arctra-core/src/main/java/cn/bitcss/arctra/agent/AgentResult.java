package cn.bitcss.arctra.agent;

import cn.bitcss.arctra.evidence.Evidence;
import java.util.Collections;
import java.util.List;

/**
 * The result of an agent execution.
 *
 * <p>Represents the outcome produced by the runtime, including the agent's response and the
 * evidence collected during execution.
 *
 * @author lov3r
 */
public record AgentResult(String content, List<Evidence> evidences) {

  public AgentResult {
    if (content == null) {
      throw new IllegalArgumentException("content cannot be null");
    }
    if (evidences == null) {
      throw new IllegalArgumentException("evidences cannot be null");
    }
    // Defensive copy to ensure immutability
    evidences = List.copyOf(evidences);
  }

  /**
   * Create an AgentResult with no evidence.
   *
   * @param content the agent's response content
   */
  public AgentResult(String content) {
    this(content, Collections.emptyList());
  }
}
