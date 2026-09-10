package cn.bitcss.arctra.checkpoint;

/**
 * Thrown when CHECK B (conditional CAS) fails during checkpoint transition.
 *
 * <p>Indicates concurrent modification detected during:
 *
 * <ul>
 *   <li>Completion: deleteIfVersion failed
 *   <li>Re-suspension: replaceIfVersion failed
 * </ul>
 *
 * @author lov3r
 * @since M5-T4
 */
public class CheckpointTransitionConflictException extends RuntimeException {

  public CheckpointTransitionConflictException(String message) {
    super(message);
  }

  public CheckpointTransitionConflictException(String message, Throwable cause) {
    super(message, cause);
  }
}
