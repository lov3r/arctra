package cn.bitcss.arctra.checkpoint;

/**
 * Thrown when checkpoint version mismatch detected during CHECK A.
 *
 * <p>Indicates the checkpoint has been modified by another operation (version advanced).
 *
 * @author lov3r
 * @since M5-T4
 */
public class StaleCheckpointException extends RuntimeException {

  public StaleCheckpointException(String message) {
    super(message);
  }

  public StaleCheckpointException(String message, Throwable cause) {
    super(message, cause);
  }
}
