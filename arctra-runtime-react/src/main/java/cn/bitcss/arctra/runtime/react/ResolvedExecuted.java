package cn.bitcss.arctra.runtime.react;

/** Resolved as executed - skip delegate, use result. */
record ResolvedExecuted(String operationId, String attemptId, String recoveredResult)
    implements RecoveryClassificationResult {
  @Override
  public RecoveryClassificationType type() {
    return RecoveryClassificationType.RESOLVED_EXECUTED;
  }
}
