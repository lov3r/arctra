package cn.bitcss.arctra.runtime;

import cn.bitcss.arctra.agent.AgentDefinition;
import cn.bitcss.arctra.agent.AgentExecutionContext;
import cn.bitcss.arctra.agent.AgentRequest;
import cn.bitcss.arctra.agent.AgentResult;
import cn.bitcss.arctra.evidence.Evidence;
import cn.bitcss.arctra.process.AgentProcess;
import cn.bitcss.arctra.process.ContinuationSignal;
import cn.bitcss.arctra.process.ContinuationSignal.ApprovalSignal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Test-only fake engine for AgentProcess lifecycle verification.
 *
 * <p>This engine simulates suspension without requiring real Spring AI integration or Governance.
 * It directly exercises the AgentProcess contract to verify that the lifecycle foundation is sound.
 *
 * <p><strong>Scope:</strong> M4-T2 test infrastructure only. Not for production use.
 *
 * <p><strong>Purpose:</strong> Prove that:
 *
 * <ul>
 *   <li>AgentProcess lifecycle contract is implementable
 *   <li>Dynamic Materialization semantic is sound
 *   <li>Process can suspend/resume correctly
 *   <li>Concurrency/failure invariants hold
 * </ul>
 *
 * <p><strong>Not simulating:</strong>
 *
 * <ul>
 *   <li>Real Spring AI Tool Calling Loop suspension
 *   <li>Governance interception (deferred to M4-T3)
 *   <li>Production suspension mechanism
 *   <li>Model reasoning continuation
 * </ul>
 *
 * @author lov3r
 * @since M4-T2
 */
class FakeSuspendingEngine implements AgentExecutionEngine {

  /** Suspension behavior mode. */
  enum SuspensionMode {
    /** Complete synchronously without process materialization. */
    COMPLETE,

    /** Suspend once, resume to completion. */
    SUSPEND_ONCE,

    /** Suspend, resume to another suspension (re-suspension capability). */
    SUSPEND_TWICE
  }

  private final SuspensionMode mode;

  FakeSuspendingEngine(SuspensionMode mode) {
    this.mode = Objects.requireNonNull(mode, "mode cannot be null");
  }

  @Override
  public AgentResult execute(
      AgentDefinition definition, AgentRequest request, AgentExecutionContext context) {

    return switch (mode) {
      case COMPLETE -> executeComplete(request);
      case SUSPEND_ONCE -> executeSuspendOnce(request);
      case SUSPEND_TWICE -> executeSuspendTwice(request);
    };
  }

  private AgentResult executeComplete(AgentRequest request) {
    // Synchronous completion - no process materialization
    String content = "Completed synchronously: " + request.userMessage();
    List<Evidence> evidences =
        List.of(new Evidence("fake:execution", "completed without suspension"));
    return new AgentResult(content, evidences);
  }

  private AgentResult executeSuspendOnce(AgentRequest request) {
    // Simulate suspension for approval
    String capturedMessage = request.userMessage();
    List<Evidence> initialEvidences =
        List.of(new Evidence("fake:suspension", "suspended for approval"));

    // Create continuation function
    Function<ContinuationSignal, AgentResult> continuation =
        signal -> {
          if (signal instanceof ApprovalSignal approval) {
            if (approval.approved()) {
              // Approved - complete execution
              String completedContent =
                  "Resumed and completed: " + capturedMessage + " (approved: " + approval.reason()
                      + ")";
              List<Evidence> finalEvidences = new ArrayList<>(initialEvidences);
              finalEvidences.add(new Evidence("fake:approval", "approved: " + approval.reason()));
              return new AgentResult(completedContent, finalEvidences);
            } else {
              // Denied - complete with denial
              String deniedContent = "Execution denied: " + approval.reason();
              List<Evidence> finalEvidences = new ArrayList<>(initialEvidences);
              finalEvidences.add(new Evidence("fake:denial", "denied: " + approval.reason()));
              return new AgentResult(deniedContent, finalEvidences);
            }
          }
          throw new IllegalArgumentException("Unexpected signal type: " + signal.getClass());
        };

    // Materialize process
    AgentProcess process = new DefaultAgentProcess(continuation);

    // Return suspended result
    return new AgentResult("Suspended pending approval", initialEvidences, process);
  }

  private AgentResult executeSuspendTwice(AgentRequest request) {
    // Simulate multi-phase suspension with stable process identity
    String phase1Message = "Phase 1: " + request.userMessage();
    List<Evidence> phase1Evidences =
        List.of(new Evidence("fake:phase1", "first suspension point"));

    // Phase 2 evidences for second suspension
    String phase2Message = "Phase 2 after: " + phase1Message;
    List<Evidence> phase2Evidences =
        List.of(
            new Evidence("fake:phase1", "phase 1 approved"),
            new Evidence("fake:phase2", "second suspension point"));

    // Phase 1 continuation
    Function<ContinuationSignal, AgentResult> continuation1 =
        signal1 -> {
          if (signal1 instanceof ApprovalSignal approval1) {
            if (approval1.approved()) {
              // Phase 1 approved - create phase 2 continuation
              Function<ContinuationSignal, AgentResult> continuation2 =
                  signal2 -> {
                    if (signal2 instanceof ApprovalSignal approval2) {
                      if (approval2.approved()) {
                        // Phase 2 approved - complete
                        String completedContent =
                            "Completed after re-suspension: "
                                + phase2Message
                                + " (phase 2 approved: "
                                + approval2.reason()
                                + ")";
                        List<Evidence> finalEvidences = new ArrayList<>(phase2Evidences);
                        finalEvidences.add(
                            new Evidence("fake:phase2_approval", "approved: " + approval2.reason()));
                        return new AgentResult(completedContent, finalEvidences);
                      } else {
                        // Phase 2 denied
                        return new AgentResult(
                            "Phase 2 denied: " + approval2.reason(), phase2Evidences);
                      }
                    }
                    throw new IllegalArgumentException(
                        "Unexpected signal type: " + signal2.getClass());
                  };

              // Return suspended with NEW temp process containing phase 2 continuation
              // DefaultAgentProcess.resume() will extract this continuation and maintain stable identity
              AgentProcess tempProcess = new DefaultAgentProcess(continuation2);
              return new AgentResult(
                  "Phase 1 approved, awaiting phase 2", phase2Evidences, tempProcess);

            } else {
              // Phase 1 denied
              return new AgentResult("Phase 1 denied: " + approval1.reason(), phase1Evidences);
            }
          }
          throw new IllegalArgumentException("Unexpected signal type: " + signal1.getClass());
        };

    // Create process with phase 1 continuation
    AgentProcess process = new DefaultAgentProcess(continuation1);

    // Return initial suspension
    return new AgentResult("Suspended phase 1", phase1Evidences, process);
  }
}
