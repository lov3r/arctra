package cn.bitcss.arctra.procedure;

import java.util.Objects;

/**
 * Defines the scope within which a reusable procedure is valid.
 *
 * <p><strong>M8-A V1 Scope:</strong> Procedures are scoped to {@link
 * cn.bitcss.arctra.agent.AgentDefinition} identity. This provides natural isolation - each agent
 * has its own reusable procedures.
 *
 * <p>Future scope expansion (multi-tenant, workspace, cross-agent) is deferred.
 *
 * @param agentName agent definition name (stable identity)
 * @author lov3r
 * @since M8-A
 */
public record ProcedureScope(String agentName) {

  public ProcedureScope {
    if (agentName == null || agentName.isBlank()) {
      throw new IllegalArgumentException("agentName cannot be null or blank");
    }
  }

  /**
   * Create scope for given agent name.
   *
   * @param agentName agent definition name
   * @return procedure scope
   */
  public static ProcedureScope forAgent(String agentName) {
    return new ProcedureScope(agentName);
  }
}
