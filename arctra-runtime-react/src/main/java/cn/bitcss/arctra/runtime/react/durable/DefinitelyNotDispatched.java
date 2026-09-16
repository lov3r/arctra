package cn.bitcss.arctra.runtime.react.durable;

/** No attempts exist - safe to execute. */
public record DefinitelyNotDispatched(String operationId) implements RecoveryClassificationResult {}
