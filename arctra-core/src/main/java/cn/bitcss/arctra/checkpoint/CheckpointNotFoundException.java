package cn.bitcss.arctra.checkpoint;

/**
 * Thrown when a checkpoint cannot be found for a given processId.
 *
 * <p>CHECK A failure condition - indicates the checkpoint was deleted or never existed.
 *
 * @author lov3r
 * @since M5-T4
 */
public class CheckpointNotFoundException extends RuntimeException {

  public CheckpointNotFoundException(String message) {
    super(message);
  }

  public CheckpointNotFoundException(String message, Throwable cause) {
    super(message, cause);
  }
}
