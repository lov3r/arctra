package cn.bitcss.arctra.runtime.react.durable;

/**
 * Exception thrown when invocation intent persistence fails.
 *
 * <p>This is a framework infrastructure failure, NOT a tool execution failure. It indicates that
 * the recovery-critical pre-call durable fact could not be recorded, and therefore physical
 * invocation must be blocked.
 *
 * <p><strong>NOT TOOL_FAILED:</strong> This exception represents gate failure before
 * delegate.call() entry. TOOL_FAILED is only emitted when delegate.call() throws.
 *
 * <p><strong>Visibility:</strong> Public for cross-package access (tests, protocol layer).
 * This is an internal contract, NOT a framework public API.
 *
 * @author lov3r
 * @since M6-T4A
 */
public class InvocationIntentPersistenceException extends RuntimeException {

  /**
   * Construct exception with message and cause.
   *
   * @param message error message
   * @param cause underlying persistence failure
   */
  public InvocationIntentPersistenceException(String message, Throwable cause) {
    super(message, cause);
  }

  /**
   * Construct exception with message only.
   *
   * @param message error message
   */
  public InvocationIntentPersistenceException(String message) {
    super(message);
  }
}
