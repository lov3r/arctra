package cn.bitcss.arctra.runtime;

import cn.bitcss.arctra.agent.AgentResult;
import cn.bitcss.arctra.process.AgentProcess;
import cn.bitcss.arctra.process.ContinuationSignal;
import java.util.Objects;
import java.util.function.Function;

/**
 * Factory for creating AgentProcess instances.
 *
 * <p>Provides public factory methods for creating suspended processes. Supports both ephemeral (M4
 * in-memory) and durable (M5 checkpoint-backed) suspension.
 *
 * @author lov3r
 * @since M4
 */
public class ProcessFactory {

  private ProcessFactory() {
    // Utility class
  }

  /**
   * Create a new suspended process with continuation function (M4 ephemeral).
   *
   * <p>Ephemeral suspension using Java closure to capture continuation state. Suitable for
   * single-JVM in-memory suspension where checkpoint persistence is not required.
   *
   * @param continuationFunction function to execute on resume
   * @return new AgentProcess in WAITING state
   * @since M4
   */
  public static AgentProcess createSuspended(
      Function<ContinuationSignal, AgentResult> continuationFunction) {
    return new DefaultAgentProcess(continuationFunction);
  }

  /**
   * Create a new durable suspended process (M5 checkpoint-backed).
   *
   * <p>Durable suspension backed by checkpoint persistence. The process can resume across
   * runtime/JVM boundaries using the durable execution engine's recovery pipeline.
   *
   * <p>The engine must implement {@link DurableExecutionEngine} and own the complete durable
   * recovery pipeline including checkpoint store, runtime binding resolver, and recovery
   * orchestration.
   *
   * @param processId stable process identifier
   * @param checkpointVersion suspension episode version (fencing token)
   * @param engine durable execution engine that owns the unified recovery pipeline
   * @return new AgentProcess in WAITING state with durable resume strategy
   * @since M5-T4
   */
  public static AgentProcess createDurableSuspended(
      String processId, long checkpointVersion, DurableExecutionEngine engine) {

    Objects.requireNonNull(processId, "processId cannot be null");
    Objects.requireNonNull(engine, "engine cannot be null");

    ResumeStrategy strategy = new DurableResumeStrategy(processId, checkpointVersion, engine);
    return DefaultAgentProcess.withStrategy(processId, strategy);
  }
}
