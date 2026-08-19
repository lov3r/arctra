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
 * <h2>M4 Implementation Strategy</h2>
 *
 * <p>This implementation uses Java closure (Function) to capture continuation state. This is a
 * pragmatic choice valid for M4's in-memory-only lifecycle scope. The closure can capture any
 * execution context needed for resumption (definition, request, context, pending actions, etc.).
 *
 * <p><strong>Stable Identity:</strong> processId remains stable across the entire task execution
 * lifecycle. Re-suspension transitions the same AgentProcess back to WAITING with updated
 * continuation, rather than creating a new process. This ensures that one task execution has one
 * processId for audit, replay, and governance purposes.
 *
 * <p><strong>Limitation:</strong> Closure-based state is not serializable and cannot survive JVM
 * restart. Future persistent process implementations (M5+) will require explicit serializable state
 * representation (e.g., ProcessState record). The migration path is clear: the public {@link
 * AgentProcess} API does not expose the closure, so internal implementation can evolve from
 * closure-based to state-based without breaking external consumers.
 *
 * <h2>Thread Safety</h2>
 *
 * <p>Uses atomic state transitions (CAS) to prevent concurrent resume execution.
 *
 * @author lov3r
 * @since M4
 */
class DefaultAgentProcess implements AgentProcess {

  private final String id;
  private final AtomicReference<ProcessStatus> status;
  private volatile Function<ContinuationSignal, AgentResult> continuationFunction;
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
        // Re-suspension: extract continuation and transition back to WAITING
          AgentProcess suspendedProcess = getSuspendedProcess(result);

          // Extract continuation from the new process
        if (suspendedProcess instanceof DefaultAgentProcess other) {
          this.continuationFunction = other.continuationFunction;
        } else {
          throw new IllegalStateException(
              "Re-suspension must return DefaultAgentProcess for continuation extraction, "
                  + "got: "
                  + suspendedProcess.getClass().getName());
        }

        // Transition back to WAITING (stable identity)
        status.set(ProcessStatus.WAITING);

        // Return result with THIS process (stable identity)
        return new AgentResult(result.content(), result.evidences(), this);

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

    private AgentProcess getSuspendedProcess(AgentResult result) {
        AgentProcess suspendedProcess = result.process();

        if (suspendedProcess == null) {
          throw new IllegalStateException(
              "Suspended AgentResult must contain process (internal contract violation)");
        }

        // Re-suspension must return different process (with new continuation)
        // Returning 'this' indicates a bug: re-suspension by definition requires
        // entering a new phase with new continuation logic, not retrying the same phase
        if (suspendedProcess == this) {
          throw new IllegalStateException(
              "Re-suspension cannot return same process - each suspension requires new continuation. "
                  + "Returning 'this' indicates a bug in continuation logic. "
                  + "Retry logic should be external to suspension mechanism.");
        }
        return suspendedProcess;
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
