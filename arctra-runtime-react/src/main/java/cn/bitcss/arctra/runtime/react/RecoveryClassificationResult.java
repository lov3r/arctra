package cn.bitcss.arctra.runtime.react;

import java.util.List;

/**
 * Recovery classification result for one logical operation.
 *
 * <p><strong>M6-T5: Enhanced classification outcomes.</strong>
 *
 * @since M6-T5
 */
sealed interface RecoveryClassificationResult
    permits DefinitelyNotDispatched,
        MayHaveInvoked,
        ResolvedExecuted,
        ResolvedNotExecuted {

  /** Get operation ID. */
  String operationId();

  /** Classification type. */
  RecoveryClassificationType type();

  /** Create DEFINITELY_NOT_DISPATCHED result. */
  static RecoveryClassificationResult definitelyNotDispatched(String operationId) {
    return new DefinitelyNotDispatched(operationId);
  }

  /** Create MAY_HAVE_INVOKED result. */
  static RecoveryClassificationResult mayHaveInvoked(
      String operationId, List<String> unresolvedAttemptIds) {
    return new MayHaveInvoked(operationId, unresolvedAttemptIds);
  }

  /** Create RESOLVED_EXECUTED result. */
  static RecoveryClassificationResult resolvedExecuted(
      String operationId, String attemptId, String recoveredResult) {
    return new ResolvedExecuted(operationId, attemptId, recoveredResult);
  }

  /** Create RESOLVED_NOT_EXECUTED result. */
  static RecoveryClassificationResult resolvedNotExecuted(String operationId) {
    return new ResolvedNotExecuted(operationId);
  }
}
