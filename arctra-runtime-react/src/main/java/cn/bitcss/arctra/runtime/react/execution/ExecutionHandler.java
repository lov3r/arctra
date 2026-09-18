package cn.bitcss.arctra.runtime.react.execution;

import cn.bitcss.arctra.agent.AgentDefinition;
import cn.bitcss.arctra.agent.AgentExecutionContext;
import cn.bitcss.arctra.agent.AgentResult;
import cn.bitcss.arctra.evidence.Evidence;
import cn.bitcss.arctra.runtime.react.governance.GovernanceToolCallingAdvisor;
import java.util.List;

/**
 * Unified interface for execution handlers (M6-T6.4 Phase 10: Interface Abstraction).
 *
 * <p>Abstracts the execution handling strategy for different execution modes (EPHEMERAL, DURABLE).
 * This interface enables:
 *
 * <ul>
 *   <li>Polymorphic execution dispatch
 *   <li>Dynamic handler registration
 *   <li>Improved testability (mock support)
 *   <li>Adherence to DIP (Dependency Inversion Principle)
 *   <li>Adherence to OCP (Open-Closed Principle)
 * </ul>
 *
 * <h2>Design Pattern:</h2>
 *
 * <p>This follows the Strategy pattern, where each handler implementation provides a specific
 * execution strategy (ephemeral in-memory vs durable checkpoint-backed).
 *
 * <h2>Implementations:</h2>
 *
 * <ul>
 *   <li>{@link EphemeralExecutionHandler} - In-memory, process-local suspension
 *   <li>{@link DurableExecutionHandler} - Checkpoint-backed, cross-JVM recovery
 * </ul>
 *
 * @since M6-T6.4 Phase 10
 */
public interface ExecutionHandler {

  /**
   * Handle ALLOW disposition (governance approved, auto-continue).
   *
   * <p>For EPHEMERAL mode, this should throw {@link UnsupportedOperationException} since ephemeral
   * mode does not support ALLOW disposition (no auto-continue without durable state).
   *
   * <p>For DURABLE mode, this materializes a RUNNABLE checkpoint and auto-continues execution.
   *
   * @param suspensionState governance state with ALLOW decision
   * @param evidences accumulated evidences
   * @param definition agent definition
   * @param context execution context
   * @return completed agent result (after auto-continue)
   * @throws UnsupportedOperationException if this handler does not support ALLOW disposition
   */
  AgentResult handleAllow(
      GovernanceToolCallingAdvisor.SuspensionState suspensionState,
      List<Evidence> evidences,
      AgentDefinition definition,
      AgentExecutionContext context);

  /**
   * Handle REQUIRE_APPROVAL disposition (governance requires external approval).
   *
   * <p>Both EPHEMERAL and DURABLE modes support this disposition:
   *
   * <ul>
   *   <li>EPHEMERAL: Creates in-memory suspended process with continuation function
   *   <li>DURABLE: Materializes WAITING checkpoint and returns durable suspended process
   * </ul>
   *
   * @param suspensionState governance state with REQUIRE_APPROVAL decision
   * @param evidences accumulated evidences
   * @param definition agent definition
   * @param context execution context
   * @return suspended agent result with process handle
   */
  AgentResult handleRequireApproval(
      GovernanceToolCallingAdvisor.SuspensionState suspensionState,
      List<Evidence> evidences,
      AgentDefinition definition,
      AgentExecutionContext context);
}
