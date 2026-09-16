package cn.bitcss.arctra.recovery;

/**
 * Resolution type for physical invocation attempts.
 *
 * <p><strong>M6-T5: Durable Recovery Resolution.</strong>
 *
 * <p>Defines the two possible outcomes for uncertain physical attempts:
 *
 * <ul>
 *   <li><strong>NOT_EXECUTED</strong>: External authority confirms the attempt did not execute.
 *       Framework will create new attempt.
 *   <li><strong>EXECUTED</strong>: External authority provides completed outcome. Framework will
 *       skip physical invocation and use recovered result.
 * </ul>
 *
 * @since M6-T5
 */
public enum ResolutionType {
  /**
   * Physical attempt did not execute.
   *
   * <p>Framework may create a new attempt and execute the logical operation.
   */
  NOT_EXECUTED,

  /**
   * Physical attempt executed successfully.
   *
   * <p>Framework will skip physical delegate invocation and continue using the recovered result.
   */
  EXECUTED
}
