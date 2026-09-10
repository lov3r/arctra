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
 * <p>Package-private implementation for process lifecycle management. Supports both ephemeral (M4
 * in-memory) and durable (M5 checkpoint-backed) suspension strategies through the {@link
 * ResumeStrategy} abstraction.
 *
 * <h2>Stable Identity</h2>
 *
 * <p>processId remains stable across the entire task execution lifecycle. Re-suspension transitions
 * the same AgentProcess back to WAITING with updated strategy, rather than creating a new process.
 * This ensures that one task execution has one processId for audit, replay, and governance
 * purposes.
 *
 * <h2>Two-Phase Resume Protocol</h2>
 *
 * <ol>
 *   <li><strong>prepare</strong> - Strategy creates lightweight immutable ResumeAttempt
 *   <li><strong>CAS</strong> - Atomic WAITING → RUNNING transition (single consumer protection)
 *   <li><strong>execute</strong> - Actual execution with potential external side effects
 * </ol>
 *
 * <p>This ensures external side effects only occur after local process ownership is established.
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
  private volatile ResumeStrategy strategy;
  private volatile AgentResult finalResult;

  /**
   * Create a new suspended process with continuation function (M4 ephemeral).
   *
   * @param continuationFunction function to execute on resume
   */
  DefaultAgentProcess(Function<ContinuationSignal, AgentResult> continuationFunction) {
    this.id = UUID.randomUUID().toString();
    this.status = new AtomicReference<>(ProcessStatus.WAITING);
    this.strategy = new EphemeralResumeStrategy(continuationFunction);
    this.finalResult = null;
  }

  /**
   * Create a new suspended process with stable ID and resume strategy.
   *
   * <p>Package-private factory method for creating processes with explicit ID and strategy,
   * primarily for durable suspension where processId is generated externally.
   *
   * @param processId stable process identifier
   * @param strategy resume strategy
   * @return new process with given ID and strategy
   */
  static DefaultAgentProcess withStrategy(String processId, ResumeStrategy strategy) {
    return new DefaultAgentProcess(processId, strategy);
  }

  /**
   * Private constructor for withStrategy factory.
   */
  private DefaultAgentProcess(String processId, ResumeStrategy strategy) {
    this.id = Objects.requireNonNull(processId, "processId cannot be null");
    this.status = new AtomicReference<>(ProcessStatus.WAITING);
    this.strategy = Objects.requireNonNull(strategy, "strategy cannot be null");
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

    // Phase 1: Prepare (lightweight, no side effects)
    ResumeAttempt attempt = strategy.prepare(signal);

    // Phase 2: Atomic transition from WAITING to RUNNING
    if (!status.compareAndSet(ProcessStatus.WAITING, ProcessStatus.RUNNING)) {
      throw new IllegalStateException(
          "Cannot resume process in state " + status.get() + " (must be WAITING)");
    }

    try {
      // Phase 3: Execute (external side effects occur here)
      AgentResult result = attempt.execute();

      // Determine final state
      if (result.isSuspended()) {
        // Re-suspension: extract strategy and transition back to WAITING
        AgentProcess suspendedProcess = getSuspendedProcess(result);

        // Extract strategy from the new process
        if (suspendedProcess instanceof DefaultAgentProcess other) {
          this.strategy = other.strategy;
        } else {
          throw new IllegalStateException(
              "Re-suspension must return DefaultAgentProcess for strategy extraction, "
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

    } catch (Throwable t) {
      // JVM-fatal errors should propagate immediately without marking process as FAILED
      if (t instanceof VirtualMachineError || t instanceof ThreadDeath) {
        throw t;
      }

      // M5-T4 Phase 6: Exception lifecycle based on strategy validity
      //
      // ResumePreparationException: Retryable preparation failure
      // - CHECK A passed, binding resolution failed
      // - No tool/model execution, no CHECK B
      // - Checkpoint unchanged, same (processId, version) remains valid
      // - Local handle remains usable → WAITING
      //
      // All other exceptions: Terminal local failure → FAILED
      // - StaleCheckpointException: local handle holds stale version
      // - CheckpointNotFoundException: no backing checkpoint exists
      // - CheckpointTransitionConflictException: lost CHECK B race, handle stale
      // - Tool/model/backend failures: execution side effects may have occurred
      //
      // CRITICAL: FAILED here means "local handle unusable", NOT necessarily
      // global logical process failure. Checkpoint is authoritative.
      if (t instanceof cn.bitcss.arctra.runtime.ResumePreparationException) {
        // Retryable preparation failure - checkpoint unchanged
        status.set(ProcessStatus.WAITING);
      } else {
        // Terminal local failure - handle unusable
        // FAILED is terminal - process cannot be resumed again
        status.set(ProcessStatus.FAILED);
      }

      // Rethrow original exception unchanged
      if (t instanceof RuntimeException) {
        throw (RuntimeException) t;
      } else {
        throw (Error) t;
      }
    }
  }

  private AgentProcess getSuspendedProcess(AgentResult result) {
    AgentProcess suspendedProcess = result.process();

    if (suspendedProcess == null) {
      throw new IllegalStateException(
          "Suspended AgentResult must contain process (internal contract violation)");
    }

    // Re-suspension must return different process (with new strategy)
    // Returning 'this' indicates a bug: re-suspension by definition requires
    // entering a new phase with new strategy logic, not retrying the same phase
    if (suspendedProcess == this) {
      throw new IllegalStateException(
          "Re-suspension cannot return same process - each suspension requires new strategy. "
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
