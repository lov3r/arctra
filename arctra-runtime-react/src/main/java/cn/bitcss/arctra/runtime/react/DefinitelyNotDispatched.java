package cn.bitcss.arctra.runtime.react;

/** No attempts exist - safe to execute. */
record DefinitelyNotDispatched(String operationId) implements RecoveryClassificationResult {
  @Override
  public RecoveryClassificationType type() {
    return RecoveryClassificationType.DEFINITELY_NOT_DISPATCHED;
  }
}
