package cn.bitcss.arctra.runtime;

import static org.assertj.core.api.Assertions.*;

import cn.bitcss.arctra.agent.AgentDefinition;
import cn.bitcss.arctra.agent.AgentExecutionContext;
import cn.bitcss.arctra.agent.AgentRequest;
import cn.bitcss.arctra.agent.AgentResult;
import cn.bitcss.arctra.checkpoint.CheckpointStore;
import cn.bitcss.arctra.checkpoint.InMemoryCheckpointStore;
import cn.bitcss.arctra.checkpoint.PendingToolCall;
import cn.bitcss.arctra.checkpoint.SuspensionCheckpoint;
import cn.bitcss.arctra.process.AgentProcess;
import cn.bitcss.arctra.process.ContinuationSignal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * M5-A3: Cross-runtime failure recovery tests.
 *
 * <p>Verifies that configuration failures in one runtime do not prevent recovery in another runtime
 * with different configuration. Demonstrates that RuntimeBindingConfigurationException is NOT
 * globally permanent.
 *
 * @author lov3r
 * @since M5-A3
 */
@DisplayName("M5-A3: Cross-Runtime Failure Recovery")
class CrossRuntimeFailureRecoveryTest {

  /**
   * Invariant: Another Runtime can resume after one Runtime has configuration failure.
   *
   * <p>Scenario:
   *
   * <ul>
   *   <li>Checkpoint requires: deploy-agent/v3
   *   <li>Runtime B has only: deploy-agent/v4 → configuration failure
   *   <li>Runtime C has: deploy-agent/v3 → resume succeeds
   * </ul>
   *
   * <p>This proves RuntimeBindingConfigurationException is relative to the runtime configuration,
   * not globally permanent for the checkpoint.
   */
  @Test
  @DisplayName("Another runtime can resume after configuration failure")
  void anotherRuntimeCanResumeAfterConfigurationFailure() {
    // Shared checkpoint store
    CheckpointStore store = new InMemoryCheckpointStore();

    // Create checkpoint requiring "deploy-agent/v3"
    SuspensionCheckpoint checkpoint =
        new SuspensionCheckpoint(
            "1.0",
            "process-1",
            1L,
            "deploy-agent/v3", // Required binding key
            "session-1",
            List.of(new PendingToolCall("test-op-X", "tc-1", "deployTool", "{}")),
            List.of());

    store.create(checkpoint);

    // Runtime B: Only has v4, missing v3
    Map<String, AgentDefinition> definitionsB = Map.of("deploy-agent/v4", new AgentDefinition("Deploy Agent v4", "V4"));

    RuntimeBindingResolver resolverB = new MapBasedRuntimeBindingResolver(definitionsB);

    DurableExecutionEngine engineB =
        new FakeDurableEngineWithResolver(store, resolverB) {
          @Override
          protected AgentResult executeResolved(
              RuntimeBinding binding, ContinuationSignal signal) {
            throw new AssertionError("Should not reach execution - resolver should fail");
          }
        };

    DurableResumeStrategy strategyB = new DurableResumeStrategy("process-1", 1L, engineB);
    AgentProcess processB = DefaultAgentProcess.withStrategy("process-1", strategyB);

    // Runtime B: Resume fails with configuration exception
    assertThatThrownBy(() -> processB.resume(new ContinuationSignal.ApprovalSignal(true, "test")))
        .isInstanceOf(ResumePreparationException.class)
        .hasCauseInstanceOf(RuntimeBindingConfigurationException.class)
        .cause()
        .hasMessageContaining("deploy-agent/v3")
        .hasMessageContaining("not found");

    // Checkpoint should remain at version 1 (unchanged)
    SuspensionCheckpoint afterB = store.load("process-1").orElseThrow();
    assertThat(afterB.checkpointVersion()).isEqualTo(1L);

    // Runtime C: Has v3
    Map<String, AgentDefinition> definitionsC =
        Map.of("deploy-agent/v3", new AgentDefinition("Deploy Agent v3", "V3"));

    RuntimeBindingResolver resolverC = new MapBasedRuntimeBindingResolver(definitionsC);

    DurableExecutionEngine engineC =
        new FakeDurableEngineWithResolver(store, resolverC) {
          @Override
          protected AgentResult executeResolved(
              RuntimeBinding binding, ContinuationSignal signal) {
            // Simulate successful tool execution and completion
            return new AgentResult("Deployment completed successfully", List.of());
          }
        };

    DurableResumeStrategy strategyC = new DurableResumeStrategy("process-1", 1L, engineC);
    AgentProcess processC = DefaultAgentProcess.withStrategy("process-1", strategyC);

    // Runtime C: Resume succeeds
    AgentResult result = processC.resume(new ContinuationSignal.ApprovalSignal(true, "test"));

    assertThat(result.isCompleted()).isTrue();
    assertThat(result.content()).contains("Deployment completed");

    // Checkpoint should be deleted after successful completion
    assertThat(store.load("process-1")).isEmpty();
  }

  /**
   * Fake durable engine that performs real resolver logic.
   */
  private abstract static class FakeDurableEngineWithResolver implements DurableExecutionEngine {
    private final CheckpointStore store;
    private final RuntimeBindingResolver resolver;

    FakeDurableEngineWithResolver(CheckpointStore store, RuntimeBindingResolver resolver) {
      this.store = store;
      this.resolver = resolver;
    }

    @Override
    public AgentResult execute(
        AgentDefinition definition, AgentRequest request, AgentExecutionContext context) {
      throw new UnsupportedOperationException("Not used in tests");
    }

    @Override
    public AgentResult resumeProcess(
        String processId, long checkpointVersion, ContinuationSignal signal) {
      // CHECK A: Load and validate checkpoint
      SuspensionCheckpoint checkpoint =
          store
              .load(processId)
              .orElseThrow(
                  () ->
                      new cn.bitcss.arctra.checkpoint.CheckpointNotFoundException(
                          "Checkpoint not found: " + processId));

      if (checkpoint.checkpointVersion() != checkpointVersion) {
        throw new cn.bitcss.arctra.checkpoint.StaleCheckpointException(
            "Version mismatch for " + processId);
      }

      // Resolve binding (may throw RuntimeBindingConfigurationException)
      RuntimeBinding binding;
      try {
        binding =
            resolver.resolve(
                processId, checkpoint.runtimeBindingKey(), checkpoint.sessionId());
      } catch (Exception e) {
        throw new ResumePreparationException(
            "RuntimeBinding resolution failed for processId " + processId, e);
      }

      // Execute and delete checkpoint on success
      AgentResult result = executeResolved(binding, signal);
      store.deleteIfVersion(processId, checkpointVersion);
      return result;
    }

    protected abstract AgentResult executeResolved(
        RuntimeBinding binding, ContinuationSignal signal);
  }
}
