package cn.bitcss.arctra.runtime;

/**
 * Thrown when the current runtime configuration cannot satisfy the requested runtimeBindingKey.
 *
 * <p>Indicates the logical binding required by the checkpoint cannot be resolved with THIS
 * runtime's current configuration (e.g., definition not present, invalid binding key format,
 * unsupported version).
 *
 * <h2>NOT Globally Permanent</h2>
 *
 * <p><strong>Critical:</strong> This exception does NOT mean the checkpoint is globally
 * unrecoverable. It means the CURRENT runtime configuration cannot satisfy the binding
 * requirement. Other runtime instances with different configurations, or the same runtime after
 * configuration updates, may successfully resolve the same checkpoint.
 *
 * <h2>Example: Cross-Runtime Recovery</h2>
 *
 * <pre>
 * Checkpoint:
 *   runtimeBindingKey = "deploy-agent/v3"
 *
 * Runtime B:
 *   definitions = {deploy-agent/v4}  ← No v3
 *   resolver.resolve(..., "deploy-agent/v3", ...)
 *   → RuntimeBindingConfigurationException
 *   → Checkpoint remains current
 *
 * Runtime C (different machine/configuration):
 *   definitions = {deploy-agent/v3}  ← Has v3
 *   resolver.resolve(..., "deploy-agent/v3", ...)
 *   → Success ✓
 * </pre>
 *
 * <h2>Retry Semantics</h2>
 *
 * <p>Immediately retrying with the SAME runtime configuration will normally fail again. Recovery
 * typically requires:
 *
 * <ul>
 *   <li>Routing to another runtime instance with appropriate configuration
 *   <li>Waiting for configuration update (e.g., deploying missing definition version)
 *   <li>Operator intervention to resolve configuration mismatch
 * </ul>
 *
 * <h2>Examples</h2>
 *
 * <ul>
 *   <li>AgentDefinition not present in current runtime
 *   <li>Invalid logical binding configuration
 *   <li>Unsupported binding version in current runtime
 *   <li>Incompatible local configuration
 *   <li>Malformed runtimeBindingKey format
 * </ul>
 *
 * <h2>Checkpoint Authority</h2>
 *
 * <p>The checkpoint remains current and valid. From the checkpoint's perspective, the durable
 * process is still WAITING and can be recovered. The failure is relative to this runtime's
 * configuration, not the checkpoint's validity.
 *
 * @author lov3r
 * @since M5-A3
 */
public class RuntimeBindingConfigurationException extends RuntimeBindingException {

  /**
   * Create exception with binding key and message.
   *
   * @param runtimeBindingKey the binding key that cannot be satisfied
   * @param message error message
   */
  public RuntimeBindingConfigurationException(String runtimeBindingKey, String message) {
    super(runtimeBindingKey, message);
  }

  /**
   * Create exception with binding key, message, and cause.
   *
   * @param runtimeBindingKey the binding key that cannot be satisfied
   * @param message error message
   * @param cause underlying cause
   */
  public RuntimeBindingConfigurationException(
      String runtimeBindingKey, String message, Throwable cause) {
    super(runtimeBindingKey, message, cause);
  }
}
