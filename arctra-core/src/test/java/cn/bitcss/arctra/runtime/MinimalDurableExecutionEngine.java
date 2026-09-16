package cn.bitcss.arctra.runtime;

import cn.bitcss.arctra.agent.AgentDefinition;
import cn.bitcss.arctra.agent.AgentExecutionContext;
import cn.bitcss.arctra.agent.AgentRequest;
import cn.bitcss.arctra.agent.AgentResult;
import cn.bitcss.arctra.process.ContinuationSignal;
import cn.bitcss.arctra.recovery.OperationResolution;
import java.util.Optional;

/**
 * Minimal DurableExecutionEngine for testing.
 *
 * <p>Provides no-op implementations of all abstract methods. Test classes can extend this
 * and override only the methods they need.
 *
 * @since M6-T5 Test support
 */
public abstract class MinimalDurableExecutionEngine implements DurableExecutionEngine {

  @Override
  public AgentResult execute(
      AgentDefinition definition, AgentRequest request, AgentExecutionContext context) {
    throw new UnsupportedOperationException("Not implemented in test stub");
  }

  @Override
  public AgentResult resumeProcess(
      String processId, long checkpointVersion, ContinuationSignal signal) {
    throw new UnsupportedOperationException("Not implemented in test stub");
  }

  @Override
  public RecoveryResolution recovery() {
    // Default: return no-op recovery resolution
    return new RecoveryResolution() {
      @Override
      public void resolveAsNotExecuted(
          String processId, long checkpointVersion, String operationId, String attemptId) {
        // No-op for tests
      }

      @Override
      public void resolveAsExecuted(
          String processId,
          long checkpointVersion,
          String operationId,
          String attemptId,
          String recoveredResult) {
        // No-op for tests
      }

      @Override
      public Optional<OperationResolution> getResolution(
          String processId, String operationId, String attemptId) {
        // Default: no resolution recorded
        return Optional.empty();
      }
    };
  }
}
