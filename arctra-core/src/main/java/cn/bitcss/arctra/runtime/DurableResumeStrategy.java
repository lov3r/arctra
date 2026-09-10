package cn.bitcss.arctra.runtime;

import cn.bitcss.arctra.agent.AgentResult;
import cn.bitcss.arctra.process.ContinuationSignal;
import java.util.Objects;

/**
 * Durable resume strategy using checkpoint-backed recovery.
 *
 * <p>Package-private M5 implementation for durable suspension. Stores only the minimal state needed
 * to delegate to {@link DurableExecutionEngine#resumeProcess} - the unified durable recovery
 * pipeline.
 *
 * <h2>Lightweight Prepare Semantics</h2>
 *
 * <p>The {@link #prepare} method is lightweight and performs NO I/O or side effects. It does NOT:
 *
 * <ul>
 *   <li>Load checkpoint from store
 *   <li>Perform CHECK A validation
 *   <li>Resolve runtime binding
 *   <li>Execute tools or call model
 *   <li>Access ChatMemory
 * </ul>
 *
 * <p>All durable orchestration logic lives in {@link DurableExecutionEngine#resumeProcess}, which
 * is called from {@link ResumeAttempt#execute} after local CAS succeeds.
 *
 * @author lov3r
 * @since M5-T4
 */
class DurableResumeStrategy implements ResumeStrategy {

  private final String processId;
  private final long checkpointVersion;
  private final DurableExecutionEngine engine;

  /**
   * Create durable resume strategy.
   *
   * @param processId stable process identifier
   * @param checkpointVersion suspension episode version (fencing token)
   * @param engine durable execution engine that owns the unified recovery pipeline
   */
  DurableResumeStrategy(
      String processId, long checkpointVersion, DurableExecutionEngine engine) {
    this.processId = Objects.requireNonNull(processId, "processId cannot be null");
    this.checkpointVersion = checkpointVersion;
    this.engine = Objects.requireNonNull(engine, "engine cannot be null");
  }

  @Override
  public ResumeAttempt prepare(ContinuationSignal signal) {
    Objects.requireNonNull(signal, "signal cannot be null");
    // Lightweight - no I/O, just capture parameters
    return new DurableAttempt(processId, checkpointVersion, signal, engine);
  }

    private record DurableAttempt(String processId, long checkpointVersion, ContinuationSignal signal,
                                  DurableExecutionEngine engine) implements ResumeAttempt {

        @Override
        public AgentResult execute() {
            // Delegate to unified durable resume pipeline
            return engine.resumeProcess(processId, checkpointVersion, signal);
        }
    }
}
