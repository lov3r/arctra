package cn.bitcss.arctra.agent;

import cn.bitcss.arctra.evidence.Evidence;
import cn.bitcss.arctra.process.AgentProcess;
import java.util.Collections;
import java.util.List;

/**
 * The result of an agent execution.
 *
 * <p>Represents the outcome produced by the runtime, including the agent's response and the
 * evidence collected during execution.
 *
 * <h2>Execution Outcomes (M4)</h2>
 *
 * <p>AgentResult can represent two outcomes:
 *
 * <ul>
 *   <li><b>Completed:</b> Execution finished synchronously (process is null)
 *   <li><b>Suspended:</b> Execution suspended, requires continuation (process is non-null)
 * </ul>
 *
 * <h2>Usage</h2>
 *
 * <pre>{@code
 * AgentResult result = agent.execute(request, context);
 *
 * // M3 pattern (still works)
 * System.out.println(result.content());
 *
 * // M4 pattern (suspension-aware)
 * if (result.isCompleted()) {
 *     System.out.println("Result: " + result.content());
 * } else if (result.isSuspended()) {
 *     AgentProcess process = result.process();
 *     // Handle suspension
 * }
 * }</pre>
 *
 * @author lov3r
 */
public record AgentResult(String content, List<Evidence> evidences, AgentProcess process) {

  public AgentResult {
    if (content == null) {
      throw new IllegalArgumentException("content cannot be null");
    }
    if (evidences == null) {
      throw new IllegalArgumentException("evidences cannot be null");
    }
    // Defensive copy to ensure immutability
    evidences = List.copyOf(evidences);
    // process may be null (completed execution)
  }

  /**
   * M3 backward compatible constructor.
   *
   * <p>Creates a completed result (no suspension).
   *
   * @param content agent response content
   * @param evidences execution evidence
   */
  public AgentResult(String content, List<Evidence> evidences) {
    this(content, evidences, null);
  }

  /**
   * Create an AgentResult with no evidence.
   *
   * @param content the agent's response content
   */
  public AgentResult(String content) {
    this(content, Collections.emptyList(), null);
  }

  /**
   * Check if execution completed synchronously.
   *
   * <p>Completed means execution finished without suspension.
   *
   * @return true if completed (process is null)
   */
  public boolean isCompleted() {
    return process == null;
  }

  /**
   * Check if execution suspended.
   *
   * <p>Suspended means execution requires continuation via AgentProcess resume.
   *
   * @return true if suspended (process is non-null)
   */
  public boolean isSuspended() {
    return process != null;
  }
}
