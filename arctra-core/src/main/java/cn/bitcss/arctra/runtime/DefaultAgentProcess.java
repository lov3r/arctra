package cn.bitcss.arctra.runtime;

import cn.bitcss.arctra.agent.AgentResult;
import cn.bitcss.arctra.process.AgentProcess;
import cn.bitcss.arctra.process.ContinuationSignal;
import cn.bitcss.arctra.process.ProcessStatus;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * Default implementation of AgentProcess.
 *
 * <p>Package-private implementation for in-memory process lifecycle management.
 *
 * <h2>Thread Safety</h2>
 *
 * <p>Uses atomic state transitions to prevent concurrent resume execution.
 *
 * @author lov3r
 * @since M4
 */
class DefaultAgentProcess implements AgentProcess {

  private final String id;
  private final AtomicReference<ProcessStatus> status;
  private final Function<ContinuationSignal, AgentResult> continuationFunction;
  private volatile AgentResult finalResult;

  /**
   * Create a new suspended process.
   *
   * @param continuationFunction function to execute on resume
   */
  DefaultAgentProcess(Function<ContinuationSignal, AgentResult> continuationFunction) {
    this.id = UUID.randomUUID().toString();
    this.status = new AtomicReference<>(ProcessStatus.WAITING);
    this.continuationFunction =
        Objects.requireNonNull(continuationFunction, "continuationFunction cannot be null");
    this.finalResult = null;
  }

  @Override
  public String id() {
    return id;
  }

  @Override
  public ProcessStatus status() {
    return status.get();
  }

  @Override
  public AgentResult resume(ContinuationSignal signal) {
    Objects.requireNonNull(signal, "signal cannot be null");

    // Atomic transition from WAITING to RUNNING
    if (!status.compareAndSet(ProcessStatus.WAITING, ProcessStatus.RUNNING)) {
      throw new IllegalStateException(
          "Cannot resume process in state " + status.get() + " (must be WAITING)");
    }

    try {
      // Execute continuation
      AgentResult result = continuationFunction.apply(signal);

      // Determine final state
      if (result.isSuspended()) {
        // Re-suspension: transition back to WAITING
        status.set(ProcessStatus.WAITING);
        // Note: re-suspended result may reference different process or same
        return result;
      } else {
        // Completion: transition to COMPLETED
        status.set(ProcessStatus.COMPLETED);
        finalResult = result;
        return result;
      }

    } catch (Exception e) {
      // Failure: transition to FAILED
      status.set(ProcessStatus.FAILED);
      throw new RuntimeException("Process execution failed during resume", e);
    }
  }

  @Override
  public AgentResult result() {
    if (status.get() != ProcessStatus.COMPLETED) {
      throw new IllegalStateException(
          "Result only available when COMPLETED (current status: " + status.get() + ")");
    }
    return finalResult;
  }
}
