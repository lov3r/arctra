package cn.bitcss.arctra.runtime;

import static cn.bitcss.arctra.checkpoint.CheckpointTestHelper.checkpoint;
import static org.assertj.core.api.Assertions.*;

import cn.bitcss.arctra.agent.AgentDefinition;
import cn.bitcss.arctra.agent.AgentExecutionContext;
import cn.bitcss.arctra.agent.AgentRequest;
import cn.bitcss.arctra.agent.AgentResult;
import cn.bitcss.arctra.checkpoint.CheckpointStore;
import cn.bitcss.arctra.checkpoint.InMemoryCheckpointStore;
import cn.bitcss.arctra.checkpoint.ContinuationDisposition;
import cn.bitcss.arctra.checkpoint.SuspensionCheckpoint;
import cn.bitcss.arctra.process.AgentProcess;
import cn.bitcss.arctra.process.ContinuationSignal;
import cn.bitcss.arctra.process.ProcessStatus;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * M5-A3: Runtime binding exception classification tests.
 *
 * <p>Verifies the semantic distinction between transient infrastructure failures and configuration
 * mismatches in runtime binding resolution.
 *
 * @author lov3r
 * @since M5-A3
 */
@DisplayName("M5-A3: Runtime Binding Exception Classification")
class RuntimeBindingExceptionClassificationTest {

  /**
   * Invariant #1: TransientRuntimeBindingException extends RuntimeBindingException.
   */
  @Test
  @DisplayName("Transient exception extends RuntimeBindingException")
  void transientExceptionExtendsRuntimeBindingException() {
    TransientRuntimeBindingException ex =
        new TransientRuntimeBindingException("key", "Timeout");

    assertThat(ex).isInstanceOf(RuntimeBindingException.class);
    assertThat(ex.runtimeBindingKey()).isEqualTo("key");
  }

  /**
   * Invariant #2: RuntimeBindingConfigurationException extends RuntimeBindingException.
   */
  @Test
  @DisplayName("Configuration exception extends RuntimeBindingException")
  void configurationExceptionExtendsRuntimeBindingException() {
    RuntimeBindingConfigurationException ex =
        new RuntimeBindingConfigurationException("key", "Not found");

    assertThat(ex).isInstanceOf(RuntimeBindingException.class);
    assertThat(ex.runtimeBindingKey()).isEqualTo("key");
  }

  /**
   * Invariant #3: Old catch(RuntimeBindingException) catches both subtypes.
   */
  @Test
  @DisplayName("Old catch(RuntimeBindingException) catches both subtypes")
  void oldCatchCatchesBothSubtypes() {
    // Transient
    try {
      throw new TransientRuntimeBindingException("key", "Timeout");
    } catch (RuntimeBindingException e) {
      assertThat(e).isInstanceOf(TransientRuntimeBindingException.class);
    }

    // Configuration
    try {
      throw new RuntimeBindingConfigurationException("key", "Not found");
    } catch (RuntimeBindingException e) {
      assertThat(e).isInstanceOf(RuntimeBindingConfigurationException.class);
    }
  }

  /**
   * Invariant #4: MapBased missing key throws RuntimeBindingConfigurationException.
   */
  @Test
  @DisplayName("MapBased missing key throws RuntimeBindingConfigurationException")
  void mapBasedMissingKeyThrowsConfigurationException() {
    RuntimeBindingResolver resolver = new MapBasedRuntimeBindingResolver(Map.of());

    assertThatThrownBy(() -> resolver.resolve("process-1", "missing-key", null))
        .isInstanceOf(RuntimeBindingConfigurationException.class)
        .hasMessageContaining("AgentDefinition not found")
        .hasMessageContaining("missing-key");
  }

  /**
   * Invariant #5: Missing key reaches application wrapped in ResumePreparationException.
   *
   * <p>Verifies the complete exception flow from resolver → engine → application.
   */
  @Test
  @DisplayName("Missing key reaches application wrapped in ResumePreparationException")
  void missingKeyReachesApplicationWrapped() {
    // Create fake durable engine that wraps configuration exception (like SpringAiToolCallingEngine does)
    DurableExecutionEngine engine =
        new FakeDurableEngine() {
          @Override
          public AgentResult resumeProcess(
              String processId, long checkpointVersion, ContinuationSignal signal) {
            try {
              throw new RuntimeBindingConfigurationException(
                  "deploy-agent/v3", "Definition not found in this runtime");
            } catch (Exception e) {
              throw new ResumePreparationException(
                  "RuntimeBinding resolution failed for processId " + processId, e);
            }
          }
        };

    DurableResumeStrategy strategy = new DurableResumeStrategy("process-1", 1L, engine);
    AgentProcess process = DefaultAgentProcess.withStrategy("process-1", strategy);

    // Resume should throw ResumePreparationException wrapping the configuration exception
    assertThatThrownBy(() -> process.resume(new ContinuationSignal.ApprovalSignal(true, "test")))
        .isInstanceOf(ResumePreparationException.class)
        .hasCauseInstanceOf(RuntimeBindingConfigurationException.class);
  }

  /**
   * Invariant #6: Wrapped cause remains RuntimeBindingConfigurationException.
   */
  @Test
  @DisplayName("Wrapped cause remains RuntimeBindingConfigurationException")
  void wrappedCauseRemainsConfigurationException() {
    DurableExecutionEngine engine =
        new FakeDurableEngine() {
          @Override
          public AgentResult resumeProcess(
              String processId, long checkpointVersion, ContinuationSignal signal) {
            try {
              throw new RuntimeBindingConfigurationException("key", "Not found");
            } catch (Exception e) {
              throw new ResumePreparationException("Binding resolution failed", e);
            }
          }
        };

    DurableResumeStrategy strategy = new DurableResumeStrategy("process-1", 1L, engine);
    AgentProcess process = DefaultAgentProcess.withStrategy("process-1", strategy);

    try {
      process.resume(new ContinuationSignal.ApprovalSignal(true, "test"));
      fail("Should throw ResumePreparationException");
    } catch (ResumePreparationException e) {
      Throwable cause = e.getCause();
      assertThat(cause).isInstanceOf(RuntimeBindingConfigurationException.class);
      assertThat(((RuntimeBindingConfigurationException) cause).runtimeBindingKey())
          .isEqualTo("key");
    }
  }

  /**
   * Invariant #7: Transient failure reaches application wrapped in ResumePreparationException.
   */
  @Test
  @DisplayName("Transient failure reaches application wrapped in ResumePreparationException")
  void transientFailureReachesApplicationWrapped() {
    DurableExecutionEngine engine =
        new FakeDurableEngine() {
          @Override
          public AgentResult resumeProcess(
              String processId, long checkpointVersion, ContinuationSignal signal) {
            try {
              throw new TransientRuntimeBindingException(
                  "key", "Config service timeout", new java.io.IOException("Timeout"));
            } catch (Exception e) {
              throw new ResumePreparationException("Binding resolution failed", e);
            }
          }
        };

    DurableResumeStrategy strategy = new DurableResumeStrategy("process-1", 1L, engine);
    AgentProcess process = DefaultAgentProcess.withStrategy("process-1", strategy);

    assertThatThrownBy(() -> process.resume(new ContinuationSignal.ApprovalSignal(true, "test")))
        .isInstanceOf(ResumePreparationException.class)
        .hasCauseInstanceOf(TransientRuntimeBindingException.class);
  }

  /**
   * Invariant #8: Transient preparation failure keeps local process WAITING.
   */
  @Test
  @DisplayName("Transient preparation failure keeps local process WAITING")
  void transientPreparationFailure_keepsWaiting() {
    DurableExecutionEngine engine =
        new FakeDurableEngine() {
          @Override
          public AgentResult resumeProcess(
              String processId, long checkpointVersion, ContinuationSignal signal) {
            try {
              throw new TransientRuntimeBindingException("key", "Service unavailable");
            } catch (Exception e) {
              throw new ResumePreparationException("Binding resolution failed", e);
            }
          }
        };

    DurableResumeStrategy strategy = new DurableResumeStrategy("process-1", 1L, engine);
    AgentProcess process = DefaultAgentProcess.withStrategy("process-1", strategy);

    assertThat(process.status()).isEqualTo(ProcessStatus.WAITING);

    assertThatThrownBy(() -> process.resume(new ContinuationSignal.ApprovalSignal(true, "test")))
        .isInstanceOf(ResumePreparationException.class);

    assertThat(process.status())
        .as("After transient failure, process should remain WAITING")
        .isEqualTo(ProcessStatus.WAITING);
  }

  /**
   * Invariant #9: Configuration failure also keeps local process WAITING.
   *
   * <p>CRITICAL: WAITING does NOT mean "current runtime will succeed on retry". It means
   * "checkpoint is still current and recovery intent is structurally valid". Another runtime or
   * configuration update may succeed.
   */
  @Test
  @DisplayName("Configuration failure also keeps local process WAITING")
  void configurationFailure_keepsWaiting() {
    DurableExecutionEngine engine =
        new FakeDurableEngine() {
          @Override
          public AgentResult resumeProcess(
              String processId, long checkpointVersion, ContinuationSignal signal) {
            try {
              throw new RuntimeBindingConfigurationException("key", "Definition not found");
            } catch (Exception e) {
              throw new ResumePreparationException("Binding resolution failed", e);
            }
          }
        };

    DurableResumeStrategy strategy = new DurableResumeStrategy("process-1", 1L, engine);
    AgentProcess process = DefaultAgentProcess.withStrategy("process-1", strategy);

    assertThat(process.status()).isEqualTo(ProcessStatus.WAITING);

    assertThatThrownBy(() -> process.resume(new ContinuationSignal.ApprovalSignal(true, "test")))
        .isInstanceOf(ResumePreparationException.class);

    assertThat(process.status())
        .as(
            "After configuration failure, process remains WAITING (checkpoint still current, other runtime may succeed)")
        .isEqualTo(ProcessStatus.WAITING);
  }

  /**
   * Invariant #10: Checkpoint remains unchanged after either preparation failure.
   */
  @Test
  @DisplayName("Checkpoint remains unchanged after preparation failure")
  void checkpointUnchangedAfterPreparationFailure() {
    CheckpointStore store = new InMemoryCheckpointStore();

    // Create checkpoint v1
    SuspensionCheckpoint checkpoint =
        checkpoint(
            "process-1",
            1L,
            "key",
            "session-1",
            ContinuationDisposition.WAITING_FOR_SIGNAL,
            List.of(new cn.bitcss.arctra.checkpoint.PendingToolCall("test-op-1", "tc-1", "tool", "{}")),
            List.of(),
            "test-epoch");

    store.create(checkpoint);

    // Engine wraps configuration exception
    DurableExecutionEngine engine =
        new FakeDurableEngine() {
          @Override
          public AgentResult resumeProcess(
              String processId, long checkpointVersion, ContinuationSignal signal) {
            try {
              throw new RuntimeBindingConfigurationException("key", "Not found");
            } catch (Exception e) {
              throw new ResumePreparationException("Binding resolution failed", e);
            }
          }
        };

    DurableResumeStrategy strategy = new DurableResumeStrategy("process-1", 1L, engine);
    AgentProcess process = DefaultAgentProcess.withStrategy("process-1", strategy);

    assertThatThrownBy(() -> process.resume(new ContinuationSignal.ApprovalSignal(true, "test")))
        .isInstanceOf(ResumePreparationException.class);

    // Checkpoint should still be at version 1
    SuspensionCheckpoint loaded = store.load("process-1").orElseThrow();
    assertThat(loaded.checkpointVersion()).isEqualTo(1L);
  }

  /**
   * Fake DurableExecutionEngine for testing.
   */
  private abstract static class FakeDurableEngine implements DurableExecutionEngine {
    @Override
    public AgentResult execute(
        AgentDefinition definition, AgentRequest request, AgentExecutionContext context) {
      throw new UnsupportedOperationException("Not used in tests");
    }

    @Override
    public RecoveryResolution recovery() {
      throw new UnsupportedOperationException("Not used in tests");
    }
  }
}
