package cn.bitcss.arctra.recovery;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Recovery resolution for one physical invocation attempt.
 *
 * <p><strong>M6-T5: Durable Recovery Resolution.</strong>
 *
 * <p>Represents the operator/reconciliation decision about an uncertain physical attempt, including
 * the resolution type and optional recovered result.
 *
 * <h2>Immutability</h2>
 *
 * <p>This is an immutable value type exposed through public recovery APIs.
 *
 * @param operationId logical operation identifier
 * @param attemptId physical attempt identifier
 * @param type resolution type (NOT_EXECUTED or EXECUTED)
 * @param recoveredResult recovered result (present only if type is EXECUTED)
 * @param resolvedAt when resolution was recorded
 * @since M6-T5
 */
public record OperationResolution(
    String operationId,
    String attemptId,
    ResolutionType type,
    Optional<String> recoveredResult,
    Instant resolvedAt) {

  /**
   * Compact constructor with validation.
   *
   * @throws NullPointerException if any required parameter is null
   * @throws IllegalArgumentException if type is EXECUTED but recoveredResult is empty
   */
  public OperationResolution {
    Objects.requireNonNull(operationId, "operationId cannot be null");
    Objects.requireNonNull(attemptId, "attemptId cannot be null");
    Objects.requireNonNull(type, "type cannot be null");
    Objects.requireNonNull(recoveredResult, "recoveredResult cannot be null");
    Objects.requireNonNull(resolvedAt, "resolvedAt cannot be null");

    if (type == ResolutionType.EXECUTED && recoveredResult.isEmpty()) {
      throw new IllegalArgumentException("EXECUTED resolution requires recoveredResult");
    }
  }

  /**
   * Create NOT_EXECUTED resolution.
   *
   * @param operationId logical operation ID
   * @param attemptId physical attempt ID
   * @param resolvedAt resolution timestamp
   * @return resolution
   */
  public static OperationResolution notExecuted(
      String operationId, String attemptId, Instant resolvedAt) {
    return new OperationResolution(
        operationId, attemptId, ResolutionType.NOT_EXECUTED, Optional.empty(), resolvedAt);
  }

  /**
   * Create EXECUTED resolution with recovered result.
   *
   * @param operationId logical operation ID
   * @param attemptId physical attempt ID
   * @param recoveredResult recovered result (non-null)
   * @param resolvedAt resolution timestamp
   * @return resolution
   */
  public static OperationResolution executed(
      String operationId, String attemptId, String recoveredResult, Instant resolvedAt) {
    Objects.requireNonNull(recoveredResult, "recoveredResult cannot be null for EXECUTED");
    return new OperationResolution(
        operationId, attemptId, ResolutionType.EXECUTED, Optional.of(recoveredResult), resolvedAt);
  }

  /**
   * Check semantic equality with another resolution.
   *
   * <p>Two resolutions are semantically equal if they have the same type and (for EXECUTED) the
   * same recovered result. This is used for idempotent resolution detection.
   *
   * @param other other resolution
   * @return true if semantically equivalent
   */
  public boolean semanticallyEquals(OperationResolution other) {
    if (this.type != other.type) {
      return false;
    }
    if (this.type == ResolutionType.EXECUTED) {
      return this.recoveredResult.equals(other.recoveredResult);
    }
    return true; // NOT_EXECUTED has no additional state
  }
}
