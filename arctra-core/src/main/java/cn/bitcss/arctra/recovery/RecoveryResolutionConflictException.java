package cn.bitcss.arctra.recovery;

/**
 * Recovery resolution conflict.
 *
 * <p><strong>M6-T5: Resolution Conflict Detection.</strong>
 *
 * <p>Thrown when attempting to record a recovery resolution that conflicts with an existing
 * resolution for the same physical attempt.
 *
 * <h2>Conflict Scenarios</h2>
 *
 * <ul>
 *   <li>Attempt already resolved as NOT_EXECUTED, new resolution is EXECUTED
 *   <li>Attempt already resolved as EXECUTED(result A), new resolution is EXECUTED(result B)
 *   <li>Attempt already resolved as EXECUTED, new resolution is NOT_EXECUTED
 * </ul>
 *
 * <h2>Idempotent Resolutions</h2>
 *
 * <p>Submitting the same semantic resolution twice (same type, same result for EXECUTED) succeeds
 * idempotently. This exception is only thrown for genuine conflicts.
 *
 * @since M6-T5
 */
public final class RecoveryResolutionConflictException extends RuntimeException {

  /**
   * Create recovery resolution conflict exception.
   *
   * @param message diagnostic message describing the conflict
   */
  public RecoveryResolutionConflictException(String message) {
    super(message);
  }

  /**
   * Create recovery resolution conflict exception with cause.
   *
   * @param message diagnostic message
   * @param cause underlying cause
   */
  public RecoveryResolutionConflictException(String message, Throwable cause) {
    super(message, cause);
  }
}
