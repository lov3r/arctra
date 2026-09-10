package cn.bitcss.arctra.runtime;

/**
 * Thrown when runtime binding cannot be resolved during durable process recovery.
 *
 * <p>Indicates the application cannot reconstruct execution dependencies for the given binding key.
 * This is typically a configuration/deployment issue rather than a process execution failure.
 *
 * @author lov3r
 * @since M5
 */
public class RuntimeBindingException extends RuntimeException {

  private final String runtimeBindingKey;

  public RuntimeBindingException(String runtimeBindingKey, String message) {
    super("Cannot resolve runtime binding '" + runtimeBindingKey + "': " + message);
    this.runtimeBindingKey = runtimeBindingKey;
  }

  public RuntimeBindingException(String runtimeBindingKey, String message, Throwable cause) {
    super("Cannot resolve runtime binding '" + runtimeBindingKey + "': " + message, cause);
    this.runtimeBindingKey = runtimeBindingKey;
  }

  public String runtimeBindingKey() {
    return runtimeBindingKey;
  }
}
