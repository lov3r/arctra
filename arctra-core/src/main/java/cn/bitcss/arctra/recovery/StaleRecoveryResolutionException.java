package cn.bitcss.arctra.recovery;

/**
 * Stale recovery resolution attempt.
 *
 * <p><strong>M6-T5: Checkpoint Version Protection.</strong>
 *
 * <p>Thrown when attempting to record a recovery resolution against a checkpoint version that no
 * longer matches the current authoritative checkpoint version.
 *
 * <h2>Scenario</h2>
 *
 * <pre>
 * Operator loads checkpoint v3 with operation op-A MAY_HAVE_INVOKED
 * Meanwhile process transitions to checkpoint v4
 * Operator submits resolution for v3
 * → StaleRecoveryResolutionException
 * </pre>
 *
 * <h2>Resolution</h2>
 *
 * <p>Operator must reload current checkpoint and re-evaluate the recovery decision based on current
 * state.
 *
 * @since M6-T5
 */
public final class StaleRecoveryResolutionException extends RuntimeException {

  /**
   * Create stale recovery resolution exception.
   *
   * @param message diagnostic message
   */
  public StaleRecoveryResolutionException(String message) {
    super(message);
  }
}
