package cn.bitcss.arctra.runtime.react.durable;

/** Resolved as executed - skip delegate, use result. */
public record ResolvedExecuted(String operationId, String attemptId, String recoveredResult)
    implements RecoveryClassificationResult {}
