package cn.bitcss.arctra.runtime;

import cn.bitcss.arctra.agent.AgentDefinition;
import cn.bitcss.arctra.agent.AgentExecutionContext;

/**
 * Runtime binding for durable recovery.
 *
 * <p>Binds a suspended process to its agent definition and execution context for recovery.
 * Resolved by {@link RuntimeBindingResolver} using the checkpoint's runtimeBindingKey.
 *
 * <p><strong>Execution authority:</strong> The {@link DurableExecutionEngine} performing recovery
 * executes using the definition and context from this binding. The binding does NOT specify which
 * engine executes - execution authority belongs to the calling durable engine.
 *
 * <p><strong>Binding key semantics:</strong> The runtimeBindingKey in checkpoints identifies the
 * logical agent configuration/binding required for recovery, not a physical runtime instance. It
 * remains stable across re-suspension episodes.
 *
 * @param definition agent definition (identity, configuration)
 * @param context execution context (session, metadata)
 * @author lov3r
 * @since M5-T4.5
 */
public record RuntimeBinding(AgentDefinition definition, AgentExecutionContext context) {

  public RuntimeBinding {
    if (definition == null) {
      throw new IllegalArgumentException("definition cannot be null");
    }
    if (context == null) {
      throw new IllegalArgumentException("context cannot be null");
    }
  }
}
