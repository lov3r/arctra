package cn.bitcss.arctra.runtime.react;

import java.util.List;

/** Unresolved attempts exist - uncertain. */
record MayHaveInvoked(String operationId, List<String> unresolvedAttemptIds)
    implements RecoveryClassificationResult {
  public MayHaveInvoked {
    unresolvedAttemptIds = List.copyOf(unresolvedAttemptIds);
  }

  @Override
  public RecoveryClassificationType type() {
    return RecoveryClassificationType.MAY_HAVE_INVOKED;
  }
}
