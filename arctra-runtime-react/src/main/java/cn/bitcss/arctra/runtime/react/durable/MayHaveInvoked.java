package cn.bitcss.arctra.runtime.react.durable;

import java.util.List;

/** Unresolved attempts exist - uncertain. */
public record MayHaveInvoked(String operationId, List<String> unresolvedAttemptIds)
    implements RecoveryClassificationResult {
  public MayHaveInvoked {
    unresolvedAttemptIds = List.copyOf(unresolvedAttemptIds);
  }
}
