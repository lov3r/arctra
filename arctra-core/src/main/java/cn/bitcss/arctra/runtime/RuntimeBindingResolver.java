package cn.bitcss.arctra.runtime;

/**
 * Runtime binding resolution for durable process recovery.
 *
 * <p>Resolves application-defined {@code runtimeBindingKey} from checkpoint to concrete runtime
 * dependencies required for process resumption. Returns {@link RuntimeBinding} containing the
 * agent definition and execution context.
 *
 * <h2>NOT a Registry</h2>
 *
 * <p>This is a resolution callback, <strong>NOT</strong> a service registry. Applications control
 * how binding keys map to runtime composition. The framework does not maintain hidden agent/engine
 * registries.
 *
 * <h2>Binding Semantics</h2>
 *
 * <p>A runtime binding provides:
 *
 * <ul>
 *   <li>{@link cn.bitcss.arctra.agent.AgentDefinition} - agent identity and configuration
 *   <li>{@link AgentExecutionContext} - session/environment context
 * </ul>
 *
 * <p>Execution authority belongs to the {@link DurableExecutionEngine} performing recovery, not
 * the binding. The binding identifies <em>what</em> to execute (definition/context), while the
 * calling engine determines <em>how</em> to execute (tools/model/policies).
 *
 * <h2>Binding Key Semantics</h2>
 *
 * <p>The binding key is application-defined and may encode:
 *
 * <ul>
 *   <li>Environment (prod, test, staging)
 *   <li>Tenant/customer identity
 *   <li>Agent type/configuration identifier
 *   <li>Version/deployment identifier
 *   <li>Any application-specific disambiguation
 * </ul>
 *
 * <p>The key remains stable across re-suspension episodes - it identifies the logical agent
 * configuration, not the physical runtime instance performing recovery.
 *
 * @author lov3r
 * @since M5
 */
@FunctionalInterface
public interface RuntimeBindingResolver {

  /**
   * Resolve runtime binding from checkpoint binding key.
   *
   * <p>Called during durable process recovery to reconstruct execution dependencies.
   *
   * @param processId the process identifier (for context/logging)
   * @param runtimeBindingKey application-defined binding key from checkpoint
   * @param sessionId session identifier from checkpoint (may be null for stateless)
   * @return resolved runtime binding with definition and context
   * @throws RuntimeBindingException if binding cannot be resolved
   */
  RuntimeBinding resolve(String processId, String runtimeBindingKey, String sessionId);
}
