package cn.bitcss.arctra.checkpoint;

/**
 * Thrown when attempting to create a checkpoint for a processId that already exists.
 *
 * <p>Indicates duplicate initial suspension attempt. This prevents accidental overwrite of active
 * checkpoints.
 *
 * @author lov3r
 * @since M5
 */
public class CheckpointAlreadyExistsException extends RuntimeException {

  private final String processId;

  public CheckpointAlreadyExistsException(String processId) {
    super("Checkpoint already exists for processId: " + processId);
    this.processId = processId;
  }

  public String processId() {
    return processId;
  }
}
