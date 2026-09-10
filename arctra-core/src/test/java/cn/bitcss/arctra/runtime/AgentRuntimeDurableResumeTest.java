package cn.bitcss.arctra.runtime;

import static org.junit.jupiter.api.Assertions.*;

import cn.bitcss.arctra.agent.AgentDefinition;
import cn.bitcss.arctra.agent.AgentExecutionContext;
import cn.bitcss.arctra.agent.AgentRequest;
import cn.bitcss.arctra.agent.AgentResult;
import cn.bitcss.arctra.process.ContinuationSignal;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Tests for Phase 3 AgentRuntime durable resumption entry.
 *
 * <p>Verifies:
 *
 * <ul>
 *   <li>AgentRuntime.resumeProcess() delegation to DurableExecutionEngine
 *   <li>UnsupportedOperationException for non-durable engines
 *   <li>Null parameter validation
 *   <li>Existing execute path unchanged
 * </ul>
 *
 * @author lov3r
 * @since M5-T4
 */
class AgentRuntimeDurableResumeTest {

  @Test
  void resumeProcess_durableEngine_delegatesToEngine() {
    var capturedProcessId = new String[1];
    var capturedVersion = new long[1];
    var capturedSignal = new ContinuationSignal[1];
    var resumeCallCount = new AtomicInteger(0);

    DurableExecutionEngine durableEngine =
        new TestDurableEngine(resumeCallCount) {
          @Override
          public AgentResult resumeProcess(
              String processId, long checkpointVersion, ContinuationSignal signal) {
            resumeCallCount.incrementAndGet();
            capturedProcessId[0] = processId;
            capturedVersion[0] = checkpointVersion;
            capturedSignal[0] = signal;
            return new AgentResult("resumed", List.of());
          }
        };

    AgentRuntime runtime = new DefaultAgentRuntime(durableEngine);
    ContinuationSignal signal = new ContinuationSignal.ApprovalSignal(true, "test-approval");

    AgentResult result = runtime.resumeProcess("P100", 7L, signal);

    assertEquals(1, resumeCallCount.get(), "Should call engine.resumeProcess() exactly once");
    assertEquals("P100", capturedProcessId[0], "Should pass correct processId");
    assertEquals(7L, capturedVersion[0], "Should pass correct checkpointVersion");
    assertSame(signal, capturedSignal[0], "Should pass exact signal object");
    assertEquals("resumed", result.content(), "Should return engine's result");
  }

  @Test
  void resumeProcess_ephemeralEngine_throwsUnsupportedOperation() {
    // Ephemeral engine (does not implement DurableExecutionEngine)
    AgentExecutionEngine ephemeralEngine =
        (definition, request, context) -> new AgentResult("ephemeral", List.of());

    AgentRuntime runtime = new DefaultAgentRuntime(ephemeralEngine);
    ContinuationSignal signal = new ContinuationSignal.ApprovalSignal(true, "test");

    UnsupportedOperationException exception =
        assertThrows(
            UnsupportedOperationException.class,
            () -> runtime.resumeProcess("P100", 1L, signal),
            "Should throw for non-durable engine");

    assertTrue(
        exception.getMessage().contains("does not support durable recovery"),
        "Exception should mention durable recovery");
  }

  @Test
  void resumeProcess_nullProcessId_throws() {
    DurableExecutionEngine durableEngine = new TestDurableEngine(new AtomicInteger());
    AgentRuntime runtime = new DefaultAgentRuntime(durableEngine);
    ContinuationSignal signal = new ContinuationSignal.ApprovalSignal(true, "test");

    assertThrows(
        NullPointerException.class,
        () -> runtime.resumeProcess(null, 1L, signal),
        "Should reject null processId");
  }

  @Test
  void resumeProcess_nullSignal_throws() {
    DurableExecutionEngine durableEngine = new TestDurableEngine(new AtomicInteger());
    AgentRuntime runtime = new DefaultAgentRuntime(durableEngine);

    assertThrows(
        NullPointerException.class,
        () -> runtime.resumeProcess("P100", 1L, null),
        "Should reject null signal");
  }

  @Test
  void execute_pathUnchanged_stillWorks() {
    var executeCallCount = new AtomicInteger(0);

    AgentExecutionEngine engine =
        (definition, request, context) -> {
          executeCallCount.incrementAndGet();
          return new AgentResult("executed", List.of());
        };

    AgentRuntime runtime = new DefaultAgentRuntime(engine);

    AgentResult result =
        runtime.execute(
            new AgentDefinition("test-agent", "test"),
            new AgentRequest("test-request"),
            AgentExecutionContext.stateless());

    assertEquals(1, executeCallCount.get(), "Should call engine.execute()");
    assertEquals("executed", result.content(), "Execute path should work unchanged");
  }

  @Test
  void constructor_compatibilityPreserved() {
    // Verify existing constructor still works
    AgentExecutionEngine engine =
        (definition, request, context) -> new AgentResult("test", List.of());

    assertDoesNotThrow(
        () -> new DefaultAgentRuntime(engine), "Existing constructor should still work");
  }

  /** Test durable engine base class for testing. */
  private static class TestDurableEngine implements DurableExecutionEngine {
    private final AtomicInteger resumeCallCount;

    TestDurableEngine(AtomicInteger resumeCallCount) {
      this.resumeCallCount = resumeCallCount;
    }

    @Override
    public AgentResult resumeProcess(
        String processId, long checkpointVersion, ContinuationSignal signal) {
      resumeCallCount.incrementAndGet();
      return new AgentResult("test-resumed", List.of());
    }

    @Override
    public AgentResult execute(
        AgentDefinition definition, AgentRequest request, AgentExecutionContext context) {
      throw new UnsupportedOperationException("Not used in these tests");
    }
  }
}
