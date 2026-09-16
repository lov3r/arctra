package cn.bitcss.arctra.runtime.react;

/** All attempts resolved as not executed - create new attempt. */
record ResolvedNotExecuted(String operationId) implements RecoveryClassificationResult {
  @Override
  public RecoveryClassificationType type() {
    return RecoveryClassificationType.RESOLVED_NOT_EXECUTED;
  }
}
