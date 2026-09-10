package cn.bitcss.arctra.runtime;

/**
 * Thrown when runtime binding resolution fails due to temporary infrastructure conditions.
 *
 * <p>Indicates the failure may recover without changing the logical recovery intent or runtime
 * configuration (e.g., config service timeout, database temporarily unavailable, network outage).
 *
 * <h2>Retry Semantics</h2>
 *
 * <p>The same resolution call with the same runtime configuration may succeed later after
 * infrastructure recovers. Transient failures are typically worth retrying with appropriate
 * backoff/delay.
 *
 * <h2>Examples</h2>
 *
 * <ul>
 *   <li>Configuration service timeout
 *   <li>Temporary database outage
 *   <li>DNS/network failure
 *   <li>Provider temporarily unavailable
 *   <li>Connection pool exhausted
 * </ul>
 *
 * <h2>Cross-Runtime Recovery</h2>
 *
 * <p>This exception does NOT indicate the checkpoint is globally unrecoverable. The same or
 * another runtime instance may successfully resolve the binding after infrastructure recovers.
 *
 * @author lov3r
 * @since M5-A3
 */
public class TransientRuntimeBindingException extends RuntimeBindingException {

  /**
   * Create exception with binding key and message.
   *
   * @param runtimeBindingKey the binding key that failed to resolve
   * @param message error message
   */
  public TransientRuntimeBindingException(String runtimeBindingKey, String message) {
    super(runtimeBindingKey, message);
  }

  /**
   * Create exception with binding key, message, and cause.
   *
   * @param runtimeBindingKey the binding key that failed to resolve
   * @param message error message
   * @param cause underlying cause (typically infrastructure exception)
   */
  public TransientRuntimeBindingException(
      String runtimeBindingKey, String message, Throwable cause) {
    super(runtimeBindingKey, message, cause);
  }
}
