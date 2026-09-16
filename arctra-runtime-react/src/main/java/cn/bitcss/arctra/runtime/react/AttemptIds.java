package cn.bitcss.arctra.runtime.react;

import java.util.UUID;

/**
 * Attempt ID generator for physical invocations.
 *
 * <p><strong>M6-T5: Physical Attempt Identity.</strong>
 *
 * <p>Generates unique identifiers for each physical invocation attempt of a logical operation.
 * Multiple attempts for the same logical operation MUST have different attemptIds.
 *
 * <h2>Uniqueness Guarantee</h2>
 *
 * <p>Uses UUID v4 to ensure:
 *
 * <ul>
 *   <li>Different workers → different attemptIds
 *   <li>Same worker, concurrent attempts → different attemptIds
 *   <li>Same worker, sequential attempts → different attemptIds
 *   <li>Restart → different attemptIds
 * </ul>
 *
 * <h2>NOT Derived From</h2>
 *
 * <p>AttemptIds are NOT derived from:
 *
 * <ul>
 *   <li>operationId (logical identity)
 *   <li>toolCallId (Spring AI protocol identity)
 *   <li>checkpointVersion
 *   <li>timestamp
 *   <li>worker ID
 * </ul>
 *
 * <p>Derivation would risk collisions across concurrent workers or retries.
 *
 * @since M6-T5
 */
final class AttemptIds {

  private AttemptIds() {
    // Utility class
  }

  /**
   * Generate unique physical attempt identifier.
   *
   * <p>Each call returns a fresh UUID, ensuring uniqueness across:
   *
   * <ul>
   *   <li>Concurrent workers
   *   <li>Sequential retries
   *   <li>Process restarts
   * </ul>
   *
   * @return unique attempt ID (UUID v4 string)
   */
  static String generate() {
    return UUID.randomUUID().toString();
  }
}
