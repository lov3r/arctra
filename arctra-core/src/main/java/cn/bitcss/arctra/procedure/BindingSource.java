package cn.bitcss.arctra.procedure;

/**
 * Source of parameter binding for procedure step execution.
 *
 * <p><strong>M8-A V1 Binding Sources:</strong>
 *
 * <ul>
 *   <li><strong>INPUT</strong> - from agent request input
 *   <li><strong>CONSTANT</strong> - explicitly allowed constant value
 *   <li><strong>PREVIOUS_STEP_OUTPUT</strong> - from previous step's extracted output
 *   <li><strong>RUNTIME_CONTEXT</strong> - from runtime execution context
 * </ul>
 *
 * <p>V1 does not support dynamic model reasoning or arbitrary expressions.
 *
 * @author lov3r
 * @since M8-A
 */
public enum BindingSource {

  /** Bind from agent request input (e.g., request.serviceName). */
  INPUT,

  /** Bind from explicitly allowed constant value. */
  CONSTANT,

  /** Bind from previous step's extracted output. */
  PREVIOUS_STEP_OUTPUT,

  /** Bind from runtime execution context (e.g., environment). */
  RUNTIME_CONTEXT
}
