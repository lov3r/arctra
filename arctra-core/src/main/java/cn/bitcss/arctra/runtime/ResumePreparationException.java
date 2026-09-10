package cn.bitcss.arctra.runtime;

/**
 * Thrown when runtime binding resolution fails during durable resume preparation.
 *
 * <p>Wraps exceptions from {@link RuntimeBindingResolver#resolve}.
 *
 * @author lov3r
 * @since M5-T4
 */
public class ResumePreparationException extends RuntimeException {

  public ResumePreparationException(String message) {
    super(message);
  }

  public ResumePreparationException(String message, Throwable cause) {
    super(message, cause);
  }
}
