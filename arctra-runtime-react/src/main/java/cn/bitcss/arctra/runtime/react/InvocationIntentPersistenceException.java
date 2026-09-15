package cn.bitcss.arctra.runtime.react;

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
 * <p>Package-private. Not part of public API.
 *
 * @author lov3r
 * @since M6-T4A
 */
class InvocationIntentPersistenceException extends RuntimeException {

  /**
   * Construct exception with message and cause.
   *
   * @param message error message
   * @param cause underlying persistence failure
   */
  InvocationIntentPersistenceException(String message, Throwable cause) {
    super(message, cause);
  }

  /**
   * Construct exception with message only.
   *
   * @param message error message
   */
  InvocationIntentPersistenceException(String message) {
    super(message);
  }
}
