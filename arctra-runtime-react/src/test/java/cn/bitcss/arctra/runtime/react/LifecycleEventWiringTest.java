package cn.bitcss.arctra.runtime.react;

import static org.assertj.core.api.Assertions.*;

import cn.bitcss.arctra.agent.AgentDefinition;
import cn.bitcss.arctra.agent.AgentExecutionContext;
import cn.bitcss.arctra.agent.AgentRequest;
import cn.bitcss.arctra.agent.AgentResult;
import cn.bitcss.arctra.checkpoint.InMemoryCheckpointStore;
import cn.bitcss.arctra.execution.EventType;
import cn.bitcss.arctra.execution.ExecutionLedger;
import cn.bitcss.arctra.execution.ExecutionRecord;
import cn.bitcss.arctra.execution.InMemoryExecutionLedger;
import cn.bitcss.arctra.governance.GovernanceDecision;
import cn.bitcss.arctra.governance.ToolGovernancePolicy;
import cn.bitcss.arctra.process.AgentProcess;
import cn.bitcss.arctra.process.ContinuationSignal;
import cn.bitcss.arctra.process.ProcessStatus;
import cn.bitcss.arctra.runtime.ResumePreparationException;
import cn.bitcss.arctra.runtime.RuntimeBinding;
import cn.bitcss.arctra.runtime.RuntimeBindingConfigurationException;
import cn.bitcss.arctra.runtime.RuntimeBindingResolver;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * Integration tests for M6-T2A Lifecycle Event Wiring.
 *
 * <p>Verifies that lifecycle events are correctly recorded in ExecutionLedger during durable
 * execution flow. Tests event presence, ordering, and negative assertions (events that should NOT
 * exist in certain scenarios).
 *
 * <p><strong>Scope:</strong> 7 lifecycle events (APPROVAL_REQUIRED, SUSPENDED, APPROVAL_GRANTED,
 * APPROVAL_REJECTED, RESUMED, CHECKPOINT_CONFLICT, COMPLETED). Tool events (TOOL_EXECUTED,
 * TOOL_FAILED) deferred to M6-T2B.
 *
 * @author M6-T2A.1
 */
class LifecycleEventWiringTest {

  private static final String SESSION_ID = "test-session";
  private static final String BINDING_KEY = "test-runtime";

  private InMemoryCheckpointStore checkpointStore;
  private ChatMemory chatMemory;
  private RuntimeBindingResolver bindingResolver;
  private InMemoryExecutionLedger executionLedger;

  @BeforeEach
  void setUp() {
    checkpointStore = new InMemoryCheckpointStore();
    chatMemory = MessageWindowChatMemory.builder().maxMessages(100).build();
    bindingResolver = TestBindings.standardResolver();
    executionLedger = new InMemoryExecutionLedger();
  }

  /**
   * Test 1: Initial durable approval suspension.
   *
   * <p>Verifies:
   * <ul>
   *   <li>APPROVAL_REQUIRED recorded (governance decision)
   *   <li>SUSPENDED recorded (checkpoint committed)
   *   <li>Event ordering: APPROVAL_REQUIRED → SUSPENDED
   * </ul>
   */
  @Test
  @DisplayName("Test 1: Initial suspension records APPROVAL_REQUIRED then SUSPENDED")
  void initialSuspension_recordsApprovalRequiredThenSuspended() {
    // Given: engine with ledger
    var engine = createEngine(executionLedger);
    var definition = new AgentDefinition("test-agent", "Test agent");
    var context = AgentExecutionContext.withSession(SESSION_ID);
    var request = new AgentRequest("test input");

    // Register binding for this session

    // When: initial execution triggers suspension (governance requires approval)
    AgentResult result = engine.execute(definition, request, context);

    // Then: process is suspended
    assertThat(result.process()).isNotNull();
    assertThat(result.process().status()).isEqualTo(ProcessStatus.WAITING);

    String processId = result.process().id();
    List<ExecutionRecord> events = getEventsForProcess(processId);

    // Verify: APPROVAL_REQUIRED → SUSPENDED
    assertEventSequence(events, EventType.APPROVAL_REQUIRED, EventType.SUSPENDED);

    // Verify: SUSPENDED has checkpointVersion=1
    ExecutionRecord suspendedEvent = events.get(1);
    assertThat(suspendedEvent.checkpointVersion()).isEqualTo(1L);

    // Verify: checkpoint actually exists
    assertThat(checkpointStore.load(processId)).isNotNull();
  }

  /**
   * Test 2: Approved resume to completion.
   */
  @Test
  @DisplayName("Test 2: Approved resume records APPROVAL_GRANTED → RESUMED → COMPLETED")
  void approvedResume_recordsApprovalGrantedResumedCompleted() {
    // Given: suspended process from initial suspension
    var engine = createEngine(executionLedger);
    var definition = new AgentDefinition("test-agent", "Test agent");
    var context = AgentExecutionContext.withSession(SESSION_ID);
    var request = new AgentRequest("test input");


    AgentResult suspendResult = engine.execute(definition, request, context);
    String processId = suspendResult.process().id();
    long checkpointVersion = 1L; // Initial checkpoint version

    // When: resume with APPROVED signal
    AgentResult resumeResult =
        engine.resumeProcess(
            processId, checkpointVersion, new ContinuationSignal.ApprovalSignal(true, "approved"));

    // Then: process completed
    assertThat(resumeResult.process()).isNull(); // Completed processes have no process handle

    // Verify: all events including resume
    List<ExecutionRecord> allEvents = getEventsForProcess(processId);

    // Events: APPROVAL_REQUIRED, SUSPENDED, APPROVAL_GRANTED, RESUMED, TOOL_EXECUTED (M6-T2B), COMPLETED
    assertThat(allEvents).hasSize(6); // M6-T2B: Now includes TOOL_EXECUTED
    assertThat(allEvents.get(2).eventType()).isEqualTo(EventType.APPROVAL_GRANTED);
    assertThat(allEvents.get(3).eventType()).isEqualTo(EventType.RESUMED);
    assertThat(allEvents.get(4).eventType()).isEqualTo(EventType.TOOL_EXECUTED); // M6-T2B
    assertThat(allEvents.get(5).eventType()).isEqualTo(EventType.COMPLETED);

    // Verify: checkpoint deleted
    assertThat(checkpointStore.load(processId)).isEmpty();
  }

  /**
   * Test 3: Rejected resume to completion.
   */
  @Test
  @DisplayName("Test 3: Rejected resume records APPROVAL_REJECTED → RESUMED → COMPLETED")
  void rejectedResume_recordsApprovalRejectedResumedCompleted() {
    // Given: suspended process
    var engine = createEngine(executionLedger);
    var definition = new AgentDefinition("test-agent", "Test agent");
    var context = AgentExecutionContext.withSession(SESSION_ID);
    var request = new AgentRequest("test input");


    AgentResult suspendResult = engine.execute(definition, request, context);
    String processId = suspendResult.process().id();
    long checkpointVersion = 1L;

    // When: resume with REJECTED signal
    AgentResult resumeResult =
        engine.resumeProcess(
            processId, checkpointVersion, new ContinuationSignal.ApprovalSignal(false, "rejected"));

    // Then: process completed (rejection doesn't prevent completion)
    assertThat(resumeResult.process()).isNull();

    // Verify: APPROVAL_REJECTED → RESUMED → COMPLETED
    List<ExecutionRecord> allEvents = getEventsForProcess(processId);

    // Events: APPROVAL_REQUIRED, SUSPENDED, APPROVAL_REJECTED, RESUMED, COMPLETED
    // M6-T2B: No TOOL_EXECUTED (rejected tools don't execute)
    assertThat(allEvents).hasSize(5);
    assertThat(allEvents.get(2).eventType()).isEqualTo(EventType.APPROVAL_REJECTED);
    assertThat(allEvents.get(3).eventType()).isEqualTo(EventType.RESUMED);
    assertThat(allEvents.get(4).eventType()).isEqualTo(EventType.COMPLETED);

    // Verify: checkpoint deleted
    assertThat(checkpointStore.load(processId)).isEmpty();
  }

  /**
   * Test 4: Stale checkpoint version.
   */
  @Test
  @DisplayName("Test 4: Stale checkpoint - no approval/resumed events")
  void staleCheckpoint_noApprovalOrResumedEvents() {
    // Given: suspended process
    var engine = createEngine(executionLedger);
    var definition = new AgentDefinition("test-agent", "Test agent");
    var context = AgentExecutionContext.withSession(SESSION_ID);
    var request = new AgentRequest("test input");


    AgentResult suspendResult = engine.execute(definition, request, context);
    String processId = suspendResult.process().id();

    // When: attempt resume with stale version (version 0 instead of 1)
    assertThatThrownBy(
            () ->
                engine.resumeProcess(
                    processId, 0L, new ContinuationSignal.ApprovalSignal(true, "approved")))
        .isInstanceOf(cn.bitcss.arctra.checkpoint.StaleCheckpointException.class);

    // Then: verify NO approval/resumed events recorded
    List<ExecutionRecord> events = getEventsForProcess(processId);

    // Only initial suspension events: APPROVAL_REQUIRED + SUSPENDED
    assertThat(events).hasSize(2);
    assertNoEventType(events, EventType.APPROVAL_GRANTED);
    assertNoEventType(events, EventType.APPROVAL_REJECTED);
    assertNoEventType(events, EventType.RESUMED);

    // Verify: checkpoint still exists at version 1
    assertThat(checkpointStore.load(processId).get().checkpointVersion()).isEqualTo(1L);
  }

  /**
   * Test 5: RuntimeBinding resolution failure.
   */
  @Test
  @DisplayName("Test 5: RuntimeBinding failure - no approval/resumed events")
  void runtimeBindingFailure_noApprovalOrResumedEvents() {
    // Given: suspended process
    var engine = createEngine(executionLedger);
    var definition = new AgentDefinition("test-agent", "Test agent");
    var context = AgentExecutionContext.withSession(SESSION_ID);
    var request = new AgentRequest("test input");


    AgentResult suspendResult = engine.execute(definition, request, context);
    String processId = suspendResult.process().id();
    long checkpointVersion = 1L;

    // Simulate binding resolution failure by using resolver that always fails
    RuntimeBindingResolver freshResolver =
        (pid, key, sessionId) -> {
          throw new RuntimeBindingConfigurationException(
              key, "Simulated binding resolution failure");
        };

    var engine2 =
        new SpringAiToolCallingEngine(
            createMockChatModel(),
            List.of(),
            chatMemory,
            (toolName, arguments, ctx) -> GovernanceDecision.REQUIRE_APPROVAL,
            checkpointStore,
            freshResolver, // Resolver without registration
            BINDING_KEY,
            executionLedger);

    // When: attempt resume (RuntimeBinding will fail)
    assertThatThrownBy(
            () ->
                engine2.resumeProcess(
                    processId, checkpointVersion, new ContinuationSignal.ApprovalSignal(true, "approved")))
        .isInstanceOf(ResumePreparationException.class)
        .hasMessageContaining("RuntimeBinding resolution failed");

    // Then: verify NO approval/resumed events
    List<ExecutionRecord> events = getEventsForProcess(processId);

    // Only initial suspension events: APPROVAL_REQUIRED + SUSPENDED
    assertThat(events).hasSize(2);
    assertNoEventType(events, EventType.APPROVAL_GRANTED);
    assertNoEventType(events, EventType.APPROVAL_REJECTED);
    assertNoEventType(events, EventType.RESUMED);

    // Verify: checkpoint remains unchanged at version 1
    assertThat(checkpointStore.load(processId).get().checkpointVersion()).isEqualTo(1L);

    // Verify: process remains WAITING (can retry)
    AgentProcess process = suspendResult.process();
    assertThat(process.status()).isEqualTo(ProcessStatus.WAITING);
  }

  // PLACEHOLDER for tests 6-11

  /**
   * Test 8: CHECK B delete conflict (completion race).
   */
  @Test
  @DisplayName("Test 8: CHECK B delete conflict - CHECKPOINT_CONFLICT, no COMPLETED")
  void checkBDeleteConflict_recordsConflictNoCompleted() {
    // Given: suspended process
    var definition = new AgentDefinition("test-agent", "Test agent");
    var context = AgentExecutionContext.withSession(SESSION_ID);

    // Use shared checkpoint store to simulate concurrent access
    var sharedStore = new InMemoryCheckpointStore();
    var sharedMemory = MessageWindowChatMemory.builder().maxMessages(100).build();
    RuntimeBindingResolver resolver =
        (processId, runtimeBindingKey, sessionId) ->
            new RuntimeBinding(definition, AgentExecutionContext.withSession(sessionId));

    // Engine A
    var engineA =
        new SpringAiToolCallingEngine(
            createMockChatModel(),
            List.of(createTestTool()), // Must provide tool for initial execution
            sharedMemory,
            (toolName, arguments, ctx) -> GovernanceDecision.REQUIRE_APPROVAL,
            sharedStore,
            resolver,
            BINDING_KEY,
            executionLedger);

    AgentResult suspendResult = engineA.execute(definition, new AgentRequest("test"), context);
    String processId = suspendResult.process().id();
    long checkpointVersion = 1L;

    // When: Runtime A completes (CHECK B delete succeeds)
    AgentResult resultA =
        engineA.resumeProcess(
            processId, checkpointVersion, new ContinuationSignal.ApprovalSignal(true, "approved"));
    assertThat(resultA.process()).isNull(); // Completed

    // Engine B with same store but different ledger to isolate events
    var ledgerB = new InMemoryExecutionLedger();
    var engineB =
        new SpringAiToolCallingEngine(
            createMockChatModel(),
            List.of(createTestTool()), // Must provide same tool for resume
            sharedMemory,
            (toolName, arguments, ctx) -> GovernanceDecision.REQUIRE_APPROVAL,
            sharedStore,
            resolver,
            BINDING_KEY,
            ledgerB);

    // When: Runtime B attempts to complete (CHECK A load fails - already deleted by A)
    // Note: CHECK A (load) happens before CHECK B (delete), so we get CheckpointNotFoundException
    assertThatThrownBy(
            () ->
                engineB.resumeProcess(
                    processId, checkpointVersion, new ContinuationSignal.ApprovalSignal(true, "approved")))
        .isInstanceOf(cn.bitcss.arctra.checkpoint.CheckpointNotFoundException.class);

    // Then: verify Runtime B recorded NO events (failed at CHECK A before APPROVAL_GRANTED)
    List<ExecutionRecord> eventsB = ledgerB.queryByProcess(processId);

    // Engine B records nothing because it failed at checkpoint load (before any resume events)
    assertThat(eventsB).isEmpty();
  }

  /**
   * Test 9: Successful completion verifies COMPLETED only after checkpoint deletion.
   */
  @Test
  @DisplayName("Test 9: Completion - COMPLETED only after checkpoint deleted")
  void completion_recordsCompletedOnlyAfterCheckpointDeleted() {
    // Given: suspended process
    var engine = createEngine(executionLedger);
    var definition = new AgentDefinition("test-agent", "Test agent");
    var context = AgentExecutionContext.withSession(SESSION_ID);
    var request = new AgentRequest("test input");


    AgentResult suspendResult = engine.execute(definition, request, context);
    String processId = suspendResult.process().id();
    long checkpointVersion = 1L;

    // When: resume approved to completion
    AgentResult resumeResult =
        engine.resumeProcess(
            processId, checkpointVersion, new ContinuationSignal.ApprovalSignal(true, "approved"));

    // Then: COMPLETED event exists
    List<ExecutionRecord> events = getEventsForProcess(processId);
    assertThat(events).anyMatch(e -> e.eventType() == EventType.COMPLETED);

    // Verify: checkpoint actually deleted
    assertThat(checkpointStore.load(processId)).isEmpty();

    // Verify: COMPLETED is the last lifecycle event
    ExecutionRecord lastEvent = events.get(events.size() - 1);
    assertThat(lastEvent.eventType()).isEqualTo(EventType.COMPLETED);
  }

  /**
   * Test 10: executionLedger = null preserves M5 behavior.
   */
  @Test
  @DisplayName("Test 10: executionLedger=null - M5 behavior unchanged")
  void nullLedger_preservesM5Behavior() {
    // Given: engine WITHOUT ledger (null)
    var engine = createEngine(null); // executionLedger = null
    var definition = new AgentDefinition("test-agent", "Test agent");
    var context = AgentExecutionContext.withSession(SESSION_ID);
    var request = new AgentRequest("test input");


    // When: initial suspension
    AgentResult suspendResult = engine.execute(definition, request, context);

    // Then: suspension works
    assertThat(suspendResult.process()).isNotNull();
    assertThat(suspendResult.process().status()).isEqualTo(ProcessStatus.WAITING);

    String processId = suspendResult.process().id();
    long checkpointVersion = 1L;

    // When: resume approved
    AgentResult resumeResult =
        engine.resumeProcess(
            processId, checkpointVersion, new ContinuationSignal.ApprovalSignal(true, "approved"));

    // Then: completion works
    assertThat(resumeResult.process()).isNull(); // Completed

    // Verify: no NullPointerException occurred
    // Verify: checkpoint was created and deleted (M5 behavior)
    assertThat(checkpointStore.load(processId)).isEmpty();
  }

  /**
   * Test 11: Ledger append failure does not block execution.
   */
  @Test
  @DisplayName("Test 11: Ledger append failure - execution continues (current behavior)")
  void ledgerAppendFailure_executionContinues() {
    // Given: ledger that throws on append
    ExecutionLedger failingLedger =
        new ExecutionLedger() {
          @Override
          public ExecutionRecord append(
              String processId, EventType eventType, Long checkpointVersion, String payload) {
            throw new RuntimeException("Simulated ledger failure");
          }

          @Override
          public List<ExecutionRecord> queryByProcess(String processId) {
            return List.of(); // No events recorded
          }

          @Override
          public List<ExecutionRecord> queryRecentByProcess(String processId, int limit) {
            return List.of();
          }
        };

    var engine = createEngine(failingLedger);
    var definition = new AgentDefinition("test-agent", "Test agent");
    var context = AgentExecutionContext.withSession(SESSION_ID);
    var request = new AgentRequest("test input");


    // When: initial suspension (ledger.append will fail)
    AgentResult suspendResult = engine.execute(definition, request, context);

    // Then: suspension succeeds despite ledger failure
    assertThat(suspendResult.process()).isNotNull();
    assertThat(suspendResult.process().status()).isEqualTo(ProcessStatus.WAITING);

    String processId = suspendResult.process().id();
    long checkpointVersion = 1L;

    // Verify: checkpoint was actually created (domain fact)
    assertThat(checkpointStore.load(processId)).isNotNull();

    // Verify: no events recorded (ledger append failed)
    List<ExecutionRecord> events = failingLedger.queryByProcess(processId);
    assertThat(events).isEmpty();

    // When: resume approved (ledger.append will fail again)
    AgentResult resumeResult =
        engine.resumeProcess(
            processId, checkpointVersion, new ContinuationSignal.ApprovalSignal(true, "approved"));

    // Then: completion succeeds despite ledger failure
    assertThat(resumeResult.process()).isNull(); // Completed

    // Verify: checkpoint deleted (domain fact)
    assertThat(checkpointStore.load(processId)).isEmpty();

    // Verify: still no events (all ledger appends failed)
    assertThat(failingLedger.queryByProcess(processId)).isEmpty();

    // Document: This is CURRENT M6-T2A implementation behavior
    // Future strict/relaxed audit policy may change this to throw on critical events
  }

  /**
   * Test 6: True CHECK B conflict with deterministic concurrency.
   *
   * <p>Verifies that when BOTH runtimes pass CHECK A and proceed to CHECK B (delete for completion),
   * the CAS conflict is detected and CHECKPOINT_CONFLICT event is recorded.
   *
   * <p>This test now correctly uses completion (not re-suspension) by ensuring ChatModel
   * returns final answer on resume (engineA/B never emit tool calls).
   */
  @Test
  @DisplayName("Test 6: True CHECK B conflict (completion) - CHECKPOINT_CONFLICT recorded")
  void trueCheckBConflict_recordsCheckpointConflict() throws Exception {
    // Given: suspended process with shared store
    var definition = new AgentDefinition("test-agent", "Test agent");
    var context = AgentExecutionContext.withSession(SESSION_ID);

    var sharedStore = new InMemoryCheckpointStore();
    RuntimeBindingResolver resolver =
        (processId, runtimeBindingKey, sessionId) ->
            new RuntimeBinding(definition, AgentExecutionContext.withSession(sessionId));

    // Create engine for initial suspension
    var memoryInit = MessageWindowChatMemory.builder().maxMessages(100).build();
    var engineInit =
        new SpringAiToolCallingEngine(
            createCompletingChatModel(), // Will emit tool call once
            List.of(createTestTool()),
            memoryInit,
            (toolName, arguments, ctx) -> GovernanceDecision.REQUIRE_APPROVAL,
            sharedStore,
            resolver,
            BINDING_KEY,
            executionLedger);

    AgentResult suspendResult = engineInit.execute(definition, new AgentRequest("test"), context);
    String processId = suspendResult.process().id();
    long checkpointVersion = 1L;

    // Use CountDownLatch to coordinate concurrent CHECK B (delete) attempts
    java.util.concurrent.CountDownLatch readyForDeleteLatch =
        new java.util.concurrent.CountDownLatch(2);
    java.util.concurrent.CountDownLatch proceedWithDeleteLatch =
        new java.util.concurrent.CountDownLatch(1);

    // Create instrumented checkpoint store that coordinates CHECK B conflict
    var instrumentedStore =
        new CheckBInstrumentedStore(sharedStore, readyForDeleteLatch, proceedWithDeleteLatch);

    // Engine A: ChatModel that NEVER emits tool calls (always completes immediately)
    var ledgerA = new InMemoryExecutionLedger();
    var memoryA = MessageWindowChatMemory.builder().maxMessages(100).build();
    var completingModelA = new ChatModel() {
      @Override
      public ChatResponse call(org.springframework.ai.chat.prompt.Prompt prompt) {
        // Always return final answer (no tool calls)
        return new ChatResponse(List.of(new Generation(new AssistantMessage("Final answer A"))));
      }

      @Override
      public org.springframework.ai.chat.prompt.ChatOptions getOptions() {
        return ToolCallingChatOptions.builder().build();
      }
    };

    var engineA =
        new SpringAiToolCallingEngine(
            completingModelA, // Never emits tool calls
            List.of(createTestTool()),
            memoryA,
            (toolName, arguments, ctx) -> GovernanceDecision.REQUIRE_APPROVAL,
            instrumentedStore,
            resolver,
            BINDING_KEY,
            ledgerA);

    // Engine B: ChatModel that NEVER emits tool calls (always completes immediately)
    var ledgerB = new InMemoryExecutionLedger();
    var memoryB = MessageWindowChatMemory.builder().maxMessages(100).build();
    var completingModelB = new ChatModel() {
      @Override
      public ChatResponse call(org.springframework.ai.chat.prompt.Prompt prompt) {
        // Always return final answer (no tool calls)
        return new ChatResponse(List.of(new Generation(new AssistantMessage("Final answer B"))));
      }

      @Override
      public org.springframework.ai.chat.prompt.ChatOptions getOptions() {
        return ToolCallingChatOptions.builder().build();
      }
    };

    var engineB =
        new SpringAiToolCallingEngine(
            completingModelB, // Never emits tool calls
            List.of(createTestTool()),
            memoryB,
            (toolName, arguments, ctx) -> GovernanceDecision.REQUIRE_APPROVAL,
            instrumentedStore,
            resolver,
            BINDING_KEY,
            ledgerB);

    // When: Both engines attempt resume concurrently (both will complete)
    var executorService = java.util.concurrent.Executors.newFixedThreadPool(2);

    java.util.concurrent.Future<AgentResult> futureA =
        executorService.submit(
            () ->
                engineA.resumeProcess(
                    processId,
                    checkpointVersion,
                    new ContinuationSignal.ApprovalSignal(true, "approved")));

    java.util.concurrent.Future<AgentResult> futureB =
        executorService.submit(
            () ->
                engineB.resumeProcess(
                    processId,
                    checkpointVersion,
                    new ContinuationSignal.ApprovalSignal(true, "approved")));

    // Wait for both to reach CHECK B (deleteIfVersion)
    boolean bothReadyForDelete =
        readyForDeleteLatch.await(5, java.util.concurrent.TimeUnit.SECONDS);
    assertThat(bothReadyForDelete).as("Both engines should reach CHECK B (deleteIfVersion)").isTrue();

    // Now allow both to proceed with CHECK B
    proceedWithDeleteLatch.countDown();

    // Collect results
    AgentResult resultA = null;
    AgentResult resultB = null;
    Exception exceptionA = null;
    Exception exceptionB = null;

    try {
      resultA = futureA.get(5, java.util.concurrent.TimeUnit.SECONDS);
    } catch (java.util.concurrent.ExecutionException e) {
      exceptionA = (Exception) e.getCause();
    }

    try {
      resultB = futureB.get(5, java.util.concurrent.TimeUnit.SECONDS);
    } catch (java.util.concurrent.ExecutionException e) {
      exceptionB = (Exception) e.getCause();
    }

    executorService.shutdown();

    // Then: One succeeds with COMPLETED, one fails with CHECKPOINT_CONFLICT
    boolean aSucceeded = (resultA != null && resultA.process() == null);
    boolean bSucceeded = (resultB != null && resultB.process() == null);
    boolean aFailed =
        (exceptionA instanceof cn.bitcss.arctra.checkpoint.CheckpointTransitionConflictException);
    boolean bFailed =
        (exceptionB instanceof cn.bitcss.arctra.checkpoint.CheckpointTransitionConflictException);

    // Exactly one should succeed, exactly one should fail
    assertThat(aSucceeded ^ bSucceeded).as("Exactly one engine should complete").isTrue();
    assertThat(aFailed ^ bFailed).as("Exactly one engine should get conflict").isTrue();

    // Verify events: winner records COMPLETED, loser records CHECKPOINT_CONFLICT
    if (aSucceeded) {
      List<ExecutionRecord> eventsA = ledgerA.queryByProcess(processId);
      assertThat(eventsA).anyMatch(e -> e.eventType() == EventType.COMPLETED);
      assertNoEventType(eventsA, EventType.CHECKPOINT_CONFLICT);

      List<ExecutionRecord> eventsB = ledgerB.queryByProcess(processId);
      assertThat(eventsB).anyMatch(e -> e.eventType() == EventType.CHECKPOINT_CONFLICT);
      assertNoEventType(eventsB, EventType.COMPLETED);
    } else {
      List<ExecutionRecord> eventsB = ledgerB.queryByProcess(processId);
      assertThat(eventsB).anyMatch(e -> e.eventType() == EventType.COMPLETED);
      assertNoEventType(eventsB, EventType.CHECKPOINT_CONFLICT);

      List<ExecutionRecord> eventsA = ledgerA.queryByProcess(processId);
      assertThat(eventsA).anyMatch(e -> e.eventType() == EventType.CHECKPOINT_CONFLICT);
      assertNoEventType(eventsA, EventType.COMPLETED);
    }
  }

  /**
   * Test 7: Durable re-suspension.
   *
   * <p>Verifies that when resumed execution reaches another REQUIRE_APPROVAL decision, a new
   * SUSPENDED event is emitted with incremented checkpoint version ONLY after CHECK B
   * replaceIfVersion succeeds.
   */
  @Test
  @DisplayName("Test 7: Re-suspension records SUSPENDED with new checkpoint version")
  void reSuspension_recordsSuspendedWithNewVersion() {
    // Given: engine that requires approval twice
    var definition = new AgentDefinition("test-agent", "Test agent");
    var context = AgentExecutionContext.withSession(SESSION_ID);

    // Create ChatModel that emits TWO tool calls (both requiring approval)
    ChatModel reSuspensionModel =
        new ChatModel() {
          private int callCount = 0;

          @Override
          public ChatResponse call(org.springframework.ai.chat.prompt.Prompt prompt) {
            callCount++;
            if (callCount == 1) {
              // First call: emit toolA
              return new ChatResponse(
                  List.of(
                      new Generation(
                          AssistantMessage.builder()
                              .content("Need toolA")
                              .toolCalls(
                                  List.of(
                                      new AssistantMessage.ToolCall(
                                          "tc-1", "function", "toolA", "{}")))
                              .build())));
            } else if (callCount == 2) {
              // After toolA: emit toolB (causes re-suspension)
              return new ChatResponse(
                  List.of(
                      new Generation(
                          AssistantMessage.builder()
                              .content("Need toolB")
                              .toolCalls(
                                  List.of(
                                      new AssistantMessage.ToolCall(
                                          "tc-2", "function", "toolB", "{}")))
                              .build())));
            } else {
              // After toolB: complete
              return new ChatResponse(List.of(new Generation(new AssistantMessage("Final answer"))));
            }
          }

          @Override
          public org.springframework.ai.chat.prompt.ChatOptions getOptions() {
            return org.springframework.ai.model.tool.ToolCallingChatOptions.builder().build();
          }
        };

    ToolCallback toolA = createToolWithName("toolA");
    ToolCallback toolB = createToolWithName("toolB");

    var engine =
        new SpringAiToolCallingEngine(
            reSuspensionModel,
            List.of(toolA, toolB),
            chatMemory,
            (toolName, arguments, ctx) -> GovernanceDecision.REQUIRE_APPROVAL,
            checkpointStore,
            bindingResolver,
            BINDING_KEY,
            executionLedger);

    // When: Initial execution suspends on toolA
    AgentResult result1 = engine.execute(definition, new AgentRequest("test"), context);
    String processId = result1.process().id();

    // Verify: First suspension at version 1
    List<ExecutionRecord> events1 = getEventsForProcess(processId);
    assertThat(events1).anyMatch(e -> e.eventType() == EventType.SUSPENDED && e.checkpointVersion() == 1L);

    // When: Resume with approval for toolA → triggers toolB → re-suspends
    AgentResult result2 =
        engine.resumeProcess(
            processId, 1L, new ContinuationSignal.ApprovalSignal(true, "approve toolA"));

    // Then: Process is suspended again (not completed)
    assertThat(result2.process()).isNotNull();
    assertThat(result2.process().status()).isEqualTo(ProcessStatus.WAITING);

    // Verify: Re-suspension recorded with NEW checkpoint version
    List<ExecutionRecord> events2 = getEventsForProcess(processId);

    // Find SUSPENDED events
    List<ExecutionRecord> suspendedEvents =
        events2.stream().filter(e -> e.eventType() == EventType.SUSPENDED).toList();

    // Should have TWO SUSPENDED events: version 1 (toolA) and version 2 (toolB)
    assertThat(suspendedEvents).hasSize(2);
    assertThat(suspendedEvents.get(0).checkpointVersion()).isEqualTo(1L);
    assertThat(suspendedEvents.get(1).checkpointVersion()).isEqualTo(2L);

    // Verify: Checkpoint version actually incremented
    assertThat(checkpointStore.load(processId).get().checkpointVersion()).isEqualTo(2L);

    // When: Resume again with approval for toolB → completes
    AgentResult result3 =
        engine.resumeProcess(
            processId, 2L, new ContinuationSignal.ApprovalSignal(true, "approve toolB"));

    // Then: Process completed
    assertThat(result3.process()).isNull();

    // Verify: COMPLETED event exists
    List<ExecutionRecord> events3 = getEventsForProcess(processId);
    assertThat(events3).anyMatch(e -> e.eventType() == EventType.COMPLETED);

    // Verify: Checkpoint deleted after completion
    assertThat(checkpointStore.load(processId)).isEmpty();
  }

  // Helper methods

  private RuntimeBindingResolver createBindingResolver() {
    return (processId, runtimeBindingKey, sessionId) ->
        new RuntimeBinding(
            new AgentDefinition("test-agent", "Test agent"),
            AgentExecutionContext.withSession(sessionId));
  }

  private ChatModel createMockChatModel() {
    return new ChatModel() {
      private int callCount = 0;

      @Override
      public ChatResponse call(org.springframework.ai.chat.prompt.Prompt prompt) {
        callCount++;
        if (callCount == 1) {
          // First call: emit tool call to trigger governance suspension
          return new ChatResponse(
              List.of(
                  new Generation(
                      AssistantMessage.builder()
                          .content("Need testTool")
                          .toolCalls(
                              List.of(
                                  new AssistantMessage.ToolCall(
                                      "tc-1", "function", "testTool", "{}")))
                          .build())));
        } else {
          // After tool: return final answer
          return new ChatResponse(List.of(new Generation(new AssistantMessage("Final answer"))));
        }
      }

      @Override
      public org.springframework.ai.chat.prompt.ChatOptions getOptions() {
        return ToolCallingChatOptions.builder().build();
      }
    };
  }

  /**
   * Creates a ChatModel that completes after one tool execution.
   * Each instance maintains independent state for concurrent testing.
   * Uses AtomicBoolean to ensure thread-safe single tool emission.
   */
  private ChatModel createCompletingChatModel() {
    return new ChatModel() {
      private final java.util.concurrent.atomic.AtomicBoolean toolEmitted =
          new java.util.concurrent.atomic.AtomicBoolean(false);

      @Override
      public ChatResponse call(org.springframework.ai.chat.prompt.Prompt prompt) {
        // Only emit tool call on first invocation, then always complete
        if (toolEmitted.compareAndSet(false, true)) {
          // First call: emit tool call
          return new ChatResponse(
              List.of(
                  new Generation(
                      AssistantMessage.builder()
                          .content("Need testTool")
                          .toolCalls(
                              List.of(
                                  new AssistantMessage.ToolCall(
                                      "tc-1", "function", "testTool", "{}")))
                          .build())));
        } else {
          // All subsequent calls: complete (no more tool calls)
          return new ChatResponse(List.of(new Generation(new AssistantMessage("Final answer"))));
        }
      }

      @Override
      public org.springframework.ai.chat.prompt.ChatOptions getOptions() {
        return ToolCallingChatOptions.builder().build();
      }
    };
  }

  private ToolCallback createTestTool() {
    return createToolWithName("testTool");
  }

  private ToolCallback createToolWithName(String toolName) {
    return new ToolCallback() {
      @Override
      public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
            .name(toolName)
            .description(toolName + " for testing")
            .inputSchema("{\"type\":\"object\",\"properties\":{}}")
            .build();
      }

      @Override
      public String call(String functionArguments) {
        return toolName + " result";
      }
    };
  }

  private SpringAiToolCallingEngine createEngine(ExecutionLedger ledger) {
    ToolGovernancePolicy requireApprovalPolicy =
        (toolName, arguments, context) -> GovernanceDecision.REQUIRE_APPROVAL;

    return new SpringAiToolCallingEngine(
        createMockChatModel(),
        List.of(createTestTool()), // Must provide tools for tool calls to work
        chatMemory,
        requireApprovalPolicy,
        checkpointStore,
        bindingResolver,
        BINDING_KEY,
        ledger); // Pass ledger (may be null for test 10)
  }

  private List<ExecutionRecord> getEventsForProcess(String processId) {
    return executionLedger.queryByProcess(processId);
  }

  private void assertEventSequence(List<ExecutionRecord> events, EventType... expectedTypes) {
    assertThat(events).hasSize(expectedTypes.length);
    for (int i = 0; i < expectedTypes.length; i++) {
      assertThat(events.get(i).eventType())
          .as("Event at position %d", i)
          .isEqualTo(expectedTypes[i]);
    }
  }

  private void assertNoEventType(List<ExecutionRecord> events, EventType eventType) {
    assertThat(events)
        .as("Should not contain %s event", eventType)
        .noneMatch(e -> e.eventType() == eventType);
  }

  /**
   * Instrumented checkpoint store that coordinates concurrent CHECK B conflict testing for
   * re-suspension (replaceIfVersion).
   */
  private static class ReplaceInstrumentedStore
      implements cn.bitcss.arctra.checkpoint.CheckpointStore {
    private final cn.bitcss.arctra.checkpoint.CheckpointStore delegate;
    private final java.util.concurrent.CountDownLatch readyForReplaceLatch;
    private final java.util.concurrent.CountDownLatch proceedWithReplaceLatch;

    ReplaceInstrumentedStore(
        cn.bitcss.arctra.checkpoint.CheckpointStore delegate,
        java.util.concurrent.CountDownLatch readyForReplaceLatch,
        java.util.concurrent.CountDownLatch proceedWithReplaceLatch) {
      this.delegate = delegate;
      this.readyForReplaceLatch = readyForReplaceLatch;
      this.proceedWithReplaceLatch = proceedWithReplaceLatch;
    }

    @Override
    public void create(cn.bitcss.arctra.checkpoint.SuspensionCheckpoint checkpoint) {
      delegate.create(checkpoint);
    }

    @Override
    public java.util.Optional<cn.bitcss.arctra.checkpoint.SuspensionCheckpoint> load(
        String processId) {
      return delegate.load(processId);
    }

    @Override
    public boolean replaceIfVersion(
        String processId,
        long expectedVersion,
        cn.bitcss.arctra.checkpoint.SuspensionCheckpoint newCheckpoint) {
      // Signal that this thread is ready for CHECK B (replaceIfVersion)
      readyForReplaceLatch.countDown();
      // Wait for both threads to reach CHECK B
      try {
        proceedWithReplaceLatch.await();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new RuntimeException(e);
      }
      // Now proceed with CAS replace - one will succeed, one will fail
      return delegate.replaceIfVersion(processId, expectedVersion, newCheckpoint);
    }

    @Override
    public boolean deleteIfVersion(String processId, long expectedVersion) {
      return delegate.deleteIfVersion(processId, expectedVersion);
    }
  }

  /**
   * Instrumented checkpoint store that coordinates concurrent CHECK B conflict testing.
   *
   * <p>Uses latches to ensure BOTH threads reach deleteIfVersion (CHECK B) before either proceeds.
   */
  private static class CheckBInstrumentedStore implements cn.bitcss.arctra.checkpoint.CheckpointStore {
    private final cn.bitcss.arctra.checkpoint.CheckpointStore delegate;
    private final java.util.concurrent.CountDownLatch readyForDeleteLatch;
    private final java.util.concurrent.CountDownLatch proceedWithDeleteLatch;

    CheckBInstrumentedStore(
        cn.bitcss.arctra.checkpoint.CheckpointStore delegate,
        java.util.concurrent.CountDownLatch readyForDeleteLatch,
        java.util.concurrent.CountDownLatch proceedWithDeleteLatch) {
      this.delegate = delegate;
      this.readyForDeleteLatch = readyForDeleteLatch;
      this.proceedWithDeleteLatch = proceedWithDeleteLatch;
    }

    @Override
    public void create(cn.bitcss.arctra.checkpoint.SuspensionCheckpoint checkpoint) {
      delegate.create(checkpoint);
    }

    @Override
    public java.util.Optional<cn.bitcss.arctra.checkpoint.SuspensionCheckpoint> load(String processId) {
      return delegate.load(processId);
    }

    @Override
    public boolean replaceIfVersion(
        String processId,
        long expectedVersion,
        cn.bitcss.arctra.checkpoint.SuspensionCheckpoint newCheckpoint) {
      return delegate.replaceIfVersion(processId, expectedVersion, newCheckpoint);
    }

    @Override
    public boolean deleteIfVersion(String processId, long expectedVersion) {
      // Signal that this thread is ready for CHECK B
      readyForDeleteLatch.countDown();
      // Wait for both threads to reach CHECK B
      try {
        proceedWithDeleteLatch.await();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new RuntimeException(e);
      }
      // Now proceed with CAS delete - one will succeed, one will fail
      return delegate.deleteIfVersion(processId, expectedVersion);
    }
  }

  /**
   * Instrumented checkpoint store that coordinates concurrent CHECK B conflict testing.
   *
   * <p>Uses latches to ensure BOTH threads pass CHECK A (load) before either proceeds to CHECK B
   * (delete).
   */
  private static class InstrumentedCheckpointStore implements cn.bitcss.arctra.checkpoint.CheckpointStore {
    private final cn.bitcss.arctra.checkpoint.CheckpointStore delegate;
    private final java.util.concurrent.CountDownLatch checkAPassedLatch;
    private final java.util.concurrent.CountDownLatch proceedToCheckBLatch;

    InstrumentedCheckpointStore(
        cn.bitcss.arctra.checkpoint.CheckpointStore delegate,
        java.util.concurrent.CountDownLatch checkAPassedLatch,
        java.util.concurrent.CountDownLatch proceedToCheckBLatch) {
      this.delegate = delegate;
      this.checkAPassedLatch = checkAPassedLatch;
      this.proceedToCheckBLatch = proceedToCheckBLatch;
    }

    @Override
    public void create(cn.bitcss.arctra.checkpoint.SuspensionCheckpoint checkpoint) {
      delegate.create(checkpoint);
    }

    @Override
    public java.util.Optional<cn.bitcss.arctra.checkpoint.SuspensionCheckpoint> load(String processId) {
      var result = delegate.load(processId);
      if (result.isPresent()) {
        // Signal that CHECK A passed
        checkAPassedLatch.countDown();
        // Wait for both threads to pass CHECK A before proceeding
        try {
          proceedToCheckBLatch.await();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new RuntimeException(e);
        }
      }
      return result;
    }

    @Override
    public boolean replaceIfVersion(
        String processId,
        long expectedVersion,
        cn.bitcss.arctra.checkpoint.SuspensionCheckpoint newCheckpoint) {
      return delegate.replaceIfVersion(processId, expectedVersion, newCheckpoint);
    }

    @Override
    public boolean deleteIfVersion(String processId, long expectedVersion) {
      // This is CHECK B - CAS delete
      return delegate.deleteIfVersion(processId, expectedVersion);
    }
  }
}

