package cn.bitcss.arctra.runtime.react.durable;

import cn.bitcss.arctra.recovery.OperationResolution;
import cn.bitcss.arctra.recovery.ResolutionType;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Internal model for one physical invocation attempt.
 *
 * <p><strong>M6-T5.1: Attempt Enumeration.</strong>
 *
 * <p>Represents one durable physical attempt for a logical operation, including its invocation
 * intent timestamp and optional recovery resolution.
 *
 * <h2>Purpose</h2>
 *
 * <p>Used by {@link InvocationRecoveryClassifier} to aggregate multiple physical attempts for one
 * logical operation and determine overall recovery classification.
 *
 * <h2>Immutability</h2>
 *
 * <p>This is an immutable value type. Do not expose publicly.
 *
 * @param attemptId unique physical attempt identifier
 * @param recordedAt when invocation intent was recorded
 * @param resolution optional recovery resolution for this attempt
 * @since M6-T5
 */
public record InvocationAttempt(
    String attemptId, Instant recordedAt, Optional<OperationResolution> resolution) {

  /**
   * Compact constructor with validation.
   *
   * @throws NullPointerException if any parameter is null
   */
  public InvocationAttempt {
    Objects.requireNonNull(attemptId, "attemptId cannot be null");
    Objects.requireNonNull(recordedAt, "recordedAt cannot be null");
    Objects.requireNonNull(resolution, "resolution cannot be null");
  }

  /**
   * Check if this attempt is unresolved.
   *
   * <p>An attempt is unresolved when invocation intent exists but no recovery resolution has been
   * recorded.
   *
   * @return true if no resolution exists
   */
  boolean isUnresolved() {
    return resolution.isEmpty();
  }

  /**
   * Check if this attempt is resolved as executed.
   *
   * @return true if resolution exists and type is EXECUTED
   */
  boolean isResolvedExecuted() {
    return resolution.isPresent() && resolution.get().type() == ResolutionType.EXECUTED;
  }

  /**
   * Check if this attempt is resolved as not executed.
   *
   * @return true if resolution exists and type is NOT_EXECUTED
   */
  boolean isResolvedNotExecuted() {
    return resolution.isPresent() && resolution.get().type() == ResolutionType.NOT_EXECUTED;
  }
}
