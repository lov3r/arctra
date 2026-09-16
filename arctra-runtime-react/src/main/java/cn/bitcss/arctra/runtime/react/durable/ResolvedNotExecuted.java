package cn.bitcss.arctra.runtime.react.durable;

/** All attempts resolved as not executed - create new attempt. */
public record ResolvedNotExecuted(String operationId) implements RecoveryClassificationResult {}
