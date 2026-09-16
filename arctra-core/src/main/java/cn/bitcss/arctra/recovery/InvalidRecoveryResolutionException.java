package cn.bitcss.arctra.recovery;

/**
 * Invalid recovery resolution.
 *
 * <p><strong>M6-T5: Resolution Validation.</strong>
 *
 * <p>Thrown when attempting to record a recovery resolution that fails validation against current
 * checkpoint and invocation state.
 *
 * <h2>Invalid Scenarios</h2>
 *
 * <ul>
 *   <li>Operation does not belong to current checkpoint's pending batch
 *   <li>Attempt does not exist for the specified operation
 *   <li>No invocation intent exists for the attempt
 *   <li>Checkpoint does not exist
 * </ul>
 *
 * @since M6-T5
 */
public final class InvalidRecoveryResolutionException extends RuntimeException {

  /**
   * Create invalid recovery resolution exception.
   *
   * @param message diagnostic message
   */
  public InvalidRecoveryResolutionException(String message) {
    super(message);
  }

  /**
   * Create invalid recovery resolution exception with cause.
   *
   * @param message diagnostic message
   * @param cause underlying cause
   */
  public InvalidRecoveryResolutionException(String message, Throwable cause) {
    super(message, cause);
  }
}
