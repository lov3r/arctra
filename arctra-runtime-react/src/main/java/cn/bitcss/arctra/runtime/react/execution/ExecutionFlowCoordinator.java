package cn.bitcss.arctra.runtime.react.execution;

import cn.bitcss.arctra.agent.AgentDefinition;
import cn.bitcss.arctra.agent.AgentExecutionContext;
import cn.bitcss.arctra.agent.AgentResult;
import cn.bitcss.arctra.checkpoint.ContinuationDisposition;
import cn.bitcss.arctra.evidence.Evidence;
import cn.bitcss.arctra.runtime.react.governance.GovernanceToolCallingAdvisor;
import java.util.List;

/**
 * Coordinator for routing execution flow based on Governance decision and Durability mode.
 *
 * <p><strong>M6-T6.4 Execution Flow Decomposition</strong>
 *
 * <p>Routes execution based on two independent dimensions:
 *
 * <ul>
 *   <li><strong>Governance:</strong> ALLOW vs REQUIRE_APPROVAL
 *   <li><strong>Durability:</strong> EPHEMERAL vs DURABLE
 * </ul>
 *
 * <p>Resulting matrix:
 *
 * <pre>
 *                      ALLOW              REQUIRE_APPROVAL
 * EPHEMERAL            NOT SUPPORTED      ephemeral wait
 * DURABLE              durable run        durable wait
 * </pre>
 *
 * <p><strong>M6-T6.4 Phase 10: Interface Abstraction</strong>
 *
 * <ul>
 *   <li>Depends on {@link ExecutionHandler} interface (DIP compliance)
 *   <li>Uses {@link ExecutionMode} enum for type-safe mode selection
 *   <li>Supports polymorphic handler dispatch
 * </ul>
 *
 * <p><strong>Responsibility:</strong>
 *
 * <ul>
 *   <li>Route based on already-determined disposition and durability mode
 *   <li>Delegate to appropriate execution handler
 *   <li>Does NOT own: governance evaluation, checkpoint authority, recovery logic
 * </ul>
 *
 * @author lov3r
 * @since M6-T6.4
 */
public class ExecutionFlowCoordinator {

  private final ExecutionHandler ephemeralHandler;
  private final ExecutionHandler durableHandler;

  public ExecutionFlowCoordinator(
      ExecutionHandler ephemeralHandler, ExecutionHandler durableHandler) {
    this.ephemeralHandler = ephemeralHandler;
    this.durableHandler = durableHandler;
  }

  /**
   * Route execution based on disposition and execution mode.
   *
   * <p><strong>M6-T6.4 Phase 10:</strong> Uses {@link ExecutionMode} enum for type-safe dispatch.
   *
   * @param suspensionState governance suspension state (contains disposition)
   * @param evidences accumulated evidences
   * @param definition agent definition
   * @param context execution context
   * @param mode execution mode (EPHEMERAL or DURABLE)
   * @return agent result (may be suspended or completed)
   * @throws IllegalStateException if disposition/mode combination is invalid
   */
  public AgentResult route(
      GovernanceToolCallingAdvisor.SuspensionState suspensionState,
      List<Evidence> evidences,
      AgentDefinition definition,
      AgentExecutionContext context,
      ExecutionMode mode) {

    ContinuationDisposition disposition = suspensionState.disposition();

    // Select handler based on execution mode
    ExecutionHandler handler = switch (mode) {
      case EPHEMERAL -> ephemeralHandler;
      case DURABLE -> durableHandler;
    };

    // Delegate to handler based on disposition
    return switch (disposition) {
      case RUNNABLE -> handler.handleAllow(suspensionState, evidences, definition, context);
      case WAITING_FOR_SIGNAL -> handler.handleRequireApproval(
          suspensionState, evidences, definition, context);
    };
  }
}
