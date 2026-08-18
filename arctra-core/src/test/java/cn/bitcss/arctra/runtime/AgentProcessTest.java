package cn.bitcss.arctra.runtime;

import static org.assertj.core.api.Assertions.*;

import cn.bitcss.arctra.agent.AgentResult;
import cn.bitcss.arctra.process.AgentProcess;
import cn.bitcss.arctra.process.ContinuationSignal;
import cn.bitcss.arctra.process.ContinuationSignal.ApprovalSignal;
import cn.bitcss.arctra.process.ProcessStatus;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for AgentProcess lifecycle.
 *
 * @author lov3r
 */
@DisplayName("AgentProcess Lifecycle Tests")
class AgentProcessTest {

  @Nested
  @DisplayName("AgentResult Backward Compatibility")
  class AgentResultCompatibilityTests {

    @Test
    @DisplayName("M3 two-arg constructor creates completed result")
    void m3ConstructorCreatesCompletedResult() {
      AgentResult result = new AgentResult("content", List.of());

      assertThat(result.content()).isEqualTo("content");
      assertThat(result.evidences()).isEmpty();
      assertThat(result.process()).isNull();
      assertThat(result.isCompleted()).isTrue();
      assertThat(result.isSuspended()).isFalse();
    }

    @Test
    @DisplayName("M3 single-arg constructor creates completed result")
    void m3SingleArgConstructorWorks() {
      AgentResult result = new AgentResult("content");

      assertThat(result.content()).isEqualTo("content");
      assertThat(result.evidences()).isEmpty();
      assertThat(result.process()).isNull();
      assertThat(result.isCompleted()).isTrue();
    }

    @Test
    @DisplayName("M4 three-arg constructor with null process creates completed result")
    void threeArgConstructorNullProcessCompleted() {
      AgentResult result = new AgentResult("content", List.of(), null);

      assertThat(result.process()).isNull();
      assertThat(result.isCompleted()).isTrue();
      assertThat(result.isSuspended()).isFalse();
    }

    @Test
    @DisplayName("M4 three-arg constructor with process creates suspended result")
    void threeArgConstructorWithProcessSuspended() {
      AgentProcess process = new DefaultAgentProcess(signal -> new AgentResult("resumed"));

      AgentResult result = new AgentResult("partial", List.of(), process);

      assertThat(result.process()).isNotNull();
      assertThat(result.process()).isSameAs(process);
      assertThat(result.isCompleted()).isFalse();
      assertThat(result.isSuspended()).isTrue();
    }
  }

  @Nested
  @DisplayName("Process Initial State")
  class InitialStateTests {

    @Test
    @DisplayName("New process starts in WAITING state")
    void newProcessStartsWaiting() {
      AgentProcess process = new DefaultAgentProcess(signal -> new AgentResult("result"));

      assertThat(process.status()).isEqualTo(ProcessStatus.WAITING);
      assertThat(process.id()).isNotNull();
      assertThat(process.id()).isNotEmpty();
    }

    @Test
    @DisplayName("Process ID is stable")
    void processIdStable() {
      AgentProcess process = new DefaultAgentProcess(signal -> new AgentResult("result"));

      String id1 = process.id();
      String id2 = process.id();

      assertThat(id1).isEqualTo(id2);
    }
  }

  @Nested
  @DisplayName("Resume to Completion")
  class ResumeCompletionTests {

    @Test
    @DisplayName("Resume WAITING process completes successfully")
    void resumeCompletesSuccessfully() {
      AgentProcess process = new DefaultAgentProcess(signal -> new AgentResult("final result"));

      ContinuationSignal signal = new ApprovalSignal(true, "approved");
      AgentResult result = process.resume(signal);

      assertThat(result.content()).isEqualTo("final result");
      assertThat(result.isCompleted()).isTrue();
      assertThat(process.status()).isEqualTo(ProcessStatus.COMPLETED);
    }

    @Test
    @DisplayName("Completed process result() returns final result")
    void completedProcessReturnsResult() {
      AgentProcess process = new DefaultAgentProcess(signal -> new AgentResult("final result"));

      process.resume(new ApprovalSignal(true, "approved"));
      AgentResult finalResult = process.result();

      assertThat(finalResult.content()).isEqualTo("final result");
    }

    @Test
    @DisplayName("Continuation receives signal")
    void continuationReceivesSignal() {
      ApprovalSignal originalSignal = new ApprovalSignal(false, "rejected");

      AgentProcess process =
          new DefaultAgentProcess(
              signal -> {
                ApprovalSignal approval = (ApprovalSignal) signal;
                return new AgentResult(
                    approval.approved() ? "approved" : "rejected: " + approval.reason());
              });

      AgentResult result = process.resume(originalSignal);

      assertThat(result.content()).isEqualTo("rejected: rejected");
    }
  }

  @Nested
  @DisplayName("Resume to Re-Suspension")
  class ResumeResuspensionTests {

    @Test
    @DisplayName("Resume can suspend again")
    void resumeCanSuspendAgain() {
      AgentProcess nestedProcess = new DefaultAgentProcess(signal -> new AgentResult("nested"));

      AgentProcess process =
          new DefaultAgentProcess(signal -> new AgentResult("partial", List.of(), nestedProcess));

      AgentResult result = process.resume(new ApprovalSignal(true, "approved"));

      assertThat(result.isSuspended()).isTrue();
      assertThat(result.process()).isSameAs(nestedProcess);
      assertThat(process.status()).isEqualTo(ProcessStatus.WAITING);
    }

    @Test
    @DisplayName("Re-suspended process can be resumed multiple times")
    void resuspendedProcessCanResumeAgain() {
      // Track resume count to implement multi-step process
      final int[] resumeCount = {0};

      AgentProcess process =
          new DefaultAgentProcess(
              signal -> {
                resumeCount[0]++;
                if (resumeCount[0] == 1) {
                  // First resume: suspend again
                  AgentProcess nextStep =
                      new DefaultAgentProcess(s -> new AgentResult("step 2 complete"));
                  return new AgentResult("step 1 partial", List.of(), nextStep);
                } else {
                  // Second+ resume: complete
                  return new AgentResult("all steps completed");
                }
              });

      // First resume - should suspend again
      AgentResult firstResult = process.resume(new ApprovalSignal(true, "step1"));
      assertThat(process.status()).isEqualTo(ProcessStatus.WAITING);
      assertThat(firstResult.isSuspended()).isTrue();

      // Second resume - should complete
      AgentResult secondResult = process.resume(new ApprovalSignal(true, "step2"));
      assertThat(process.status()).isEqualTo(ProcessStatus.COMPLETED);
      assertThat(secondResult.isCompleted()).isTrue();
    }
  }

  @Nested
  @DisplayName("Resume Failure")
  class ResumeFailureTests {

    @Test
    @DisplayName("Exception during continuation transitions to FAILED")
    void exceptionTransitionsToFailed() {
      AgentProcess process =
          new DefaultAgentProcess(
              signal -> {
                throw new RuntimeException("Simulated failure");
              });

      assertThatThrownBy(() -> process.resume(new ApprovalSignal(true, "approved")))
          .isInstanceOf(RuntimeException.class)
          .hasMessageContaining("Process execution failed");

      assertThat(process.status()).isEqualTo(ProcessStatus.FAILED);
    }

    @Test
    @DisplayName("FAILED process cannot be resumed")
    void failedProcessCannotResume() {
      AgentProcess process =
          new DefaultAgentProcess(
              signal -> {
                throw new RuntimeException("Fail");
              });

      assertThatThrownBy(() -> process.resume(new ApprovalSignal(true, "approved")));

      assertThatThrownBy(() -> process.resume(new ApprovalSignal(true, "retry")))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("must be WAITING");
    }
  }

  @Nested
  @DisplayName("Illegal State Transitions")
  class IllegalTransitionTests {

    @Test
    @DisplayName("Resume COMPLETED process throws")
    void resumeCompletedThrows() {
      AgentProcess process = new DefaultAgentProcess(signal -> new AgentResult("result"));
      process.resume(new ApprovalSignal(true, "approved"));

      assertThatThrownBy(() -> process.resume(new ApprovalSignal(true, "again")))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("must be WAITING");
    }

    @Test
    @DisplayName("result() on WAITING process throws")
    void resultOnWaitingThrows() {
      AgentProcess process = new DefaultAgentProcess(signal -> new AgentResult("result"));

      assertThatThrownBy(process::result)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("only available when COMPLETED");
    }

    @Test
    @DisplayName("result() on FAILED process throws")
    void resultOnFailedThrows() {
      AgentProcess process =
          new DefaultAgentProcess(
              signal -> {
                throw new RuntimeException("Fail");
              });

      assertThatThrownBy(() -> process.resume(new ApprovalSignal(true, "approved")));

      assertThatThrownBy(process::result)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("only available when COMPLETED");
    }

    @Test
    @DisplayName("resume with null signal throws")
    void resumeNullSignalThrows() {
      AgentProcess process = new DefaultAgentProcess(signal -> new AgentResult("result"));

      assertThatThrownBy(() -> process.resume(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("signal cannot be null");
    }
  }

  @Nested
  @DisplayName("Process Identity")
  class IdentityTests {

    @Test
    @DisplayName("Process ID remains same across resume and re-suspension")
    void processIdStableAcrossResume() {
      AgentProcess nestedProcess = new DefaultAgentProcess(signal -> new AgentResult("nested"));
      AgentProcess process =
          new DefaultAgentProcess(signal -> new AgentResult("partial", List.of(), nestedProcess));

      String idBefore = process.id();
      process.resume(new ApprovalSignal(true, "approved"));
      String idAfter = process.id();

      assertThat(idAfter).isEqualTo(idBefore);
    }

    @Test
    @DisplayName("Different processes have different IDs")
    void differentProcessesHaveDifferentIds() {
      AgentProcess process1 = new DefaultAgentProcess(signal -> new AgentResult("1"));
      AgentProcess process2 = new DefaultAgentProcess(signal -> new AgentResult("2"));

      assertThat(process1.id()).isNotEqualTo(process2.id());
    }
  }

  @Nested
  @DisplayName("Concurrency")
  class ConcurrencyTests {

    @Test
    @DisplayName("Concurrent resume attempts rejected")
    void concurrentResumeRejected() throws Exception {
      AgentProcess process =
          new DefaultAgentProcess(
              signal -> {
                try {
                  Thread.sleep(50); // Simulate work
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
                return new AgentResult("result");
              });

      Thread thread1 =
          new Thread(
              () -> {
                try {
                  process.resume(new ApprovalSignal(true, "thread1"));
                } catch (Exception ignored) {
                }
              });

      Thread thread2 =
          new Thread(
              () -> {
                try {
                  Thread.sleep(10); // Slight delay
                  process.resume(new ApprovalSignal(true, "thread2"));
                } catch (Exception ignored) {
                }
              });

      thread1.start();
      thread2.start();
      thread1.join();
      thread2.join();

      // Process should be COMPLETED (first thread succeeded)
      // Second thread should have thrown (caught and ignored)
      assertThat(process.status()).isEqualTo(ProcessStatus.COMPLETED);
    }
  }
}
