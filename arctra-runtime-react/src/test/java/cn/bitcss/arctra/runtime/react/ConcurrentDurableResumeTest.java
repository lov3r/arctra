package cn.bitcss.arctra.runtime.react;

import static org.assertj.core.api.Assertions.*;

import cn.bitcss.arctra.agent.AgentDefinition;
import cn.bitcss.arctra.agent.AgentExecutionContext;
import cn.bitcss.arctra.agent.AgentRequest;
import cn.bitcss.arctra.agent.AgentResult;
import cn.bitcss.arctra.checkpoint.CheckpointStore;
import cn.bitcss.arctra.checkpoint.CheckpointTransitionConflictException;
import cn.bitcss.arctra.checkpoint.PendingToolCall;
import cn.bitcss.arctra.checkpoint.StaleCheckpointException;
import cn.bitcss.arctra.checkpoint.SuspensionCheckpoint;
import cn.bitcss.arctra.governance.GovernanceDecision;
import cn.bitcss.arctra.governance.ToolGovernancePolicy;
import cn.bitcss.arctra.process.AgentProcess;
import cn.bitcss.arctra.process.ContinuationSignal;
import cn.bitcss.arctra.process.ProcessStatus;
import cn.bitcss.arctra.runtime.DefaultAgentRuntime;
import cn.bitcss.arctra.runtime.RuntimeBinding;
import cn.bitcss.arctra.runtime.RuntimeBindingResolver;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * M5-T4 Phase 8: Concurrent durable resume tests.
 *
 * <p>Verifies concurrency semantics for:
 *
 * <ul>
 *   <li>Same local AgentProcess handle (CAS protection)
 *   <li>Cross-runtime completion race (CHECK B winner)
 *   <li>Cross-runtime re-suspension race (CHECK B winner)
 *   <li>ChatMemory writes (only winner)
 *   <li>Evidence semantics under conflict
 * </ul>
 *
 * <p><strong>Frozen Contract:</strong>
 *
 * <ul>
 *   <li>Same local handle: CAS ensures one backend execution
 *   <li>Cross-runtime: both may execute tools, one CHECK B winner
 *   <li>M5 does NOT guarantee exactly-once tool execution
 *   <li>Tools should be idempotent
 * </ul>
 *
 * @author lov3r
 * @since M5-T4 Phase 8
 */
@DisplayName("M5-T4 Phase 8: Concurrent Durable Resume")
class ConcurrentDurableResumeTest {

  private static final String SESSION_ID = "session-123";
  private static final String BINDING_KEY = "test-agent";

  /**
   * Same local handle: concurrent resume() → only one backend execution.
   *
   * <p>Local CAS (WAITING → RUNNING) protects backend invocation.
   */
  @Test
  @DisplayName("Same handle concurrent resume - only one backend invocation")
  void sameHandle_concurrentResume_onlyOneBackendInvocation() throws Exception {
    SharedCheckpointStore store = new SharedCheckpointStore();
    SharedChatMemory memory = new SharedChatMemory();

    // Create suspended process
    AtomicInteger toolExecutionCount = new AtomicInteger(0);
    AtomicInteger modelCallCount = new AtomicInteger(0);
    AtomicInteger backendResumeCount = new AtomicInteger(0);

    // Model that emits toolX requiring approval, then toolY requiring approval (keeps suspended)
    AtomicInteger modelInvocationCount = new AtomicInteger(0);
    ChatModel model = new ChatModel() {
      @Override
      public ChatResponse call(org.springframework.ai.chat.prompt.Prompt prompt) {
        int invocation = modelInvocationCount.incrementAndGet();
        if (invocation == 1) {
          // First call: emit toolX
          return new ChatResponse(
              List.of(
                  new Generation(
                      AssistantMessage.builder()
                          .content("Need toolX")
                          .toolCalls(
                              List.of(
                                  new AssistantMessage.ToolCall(
                                      "tc-1", "function", "toolX", "{}")))
                          .build())));
        } else {
          // Subsequent calls: emit toolY (keeps process suspended)
          return new ChatResponse(
              List.of(
                  new Generation(
                      AssistantMessage.builder()
                          .content("Need toolY")
                          .toolCalls(
                              List.of(
                                  new AssistantMessage.ToolCall(
                                      "tc-2", "function", "toolY", "{}")))
                          .build())));
        }
      }

      @Override
      public org.springframework.ai.chat.prompt.ChatOptions getOptions() {
        return org.springframework.ai.model.tool.ToolCallingChatOptions.builder().build();
      }
    };

    SpringAiToolCallingEngine engine =
        new SpringAiToolCallingEngine(
            model,
            List.of(createTool("toolX", toolExecutionCount), createTool("toolY", new AtomicInteger())),
            memory,
            (t, a, c) -> GovernanceDecision.REQUIRE_APPROVAL,
            store,
            createResolver(new AtomicInteger()),
            BINDING_KEY) {
          @Override
          public AgentResult resumeProcess(
              String processId, long checkpointVersion, ContinuationSignal signal) {
            backendResumeCount.incrementAndGet();
            return super.resumeProcess(processId, checkpointVersion, signal);
          }
        };

    DefaultAgentRuntime runtime = new DefaultAgentRuntime(engine);

    // Initial suspend
    AgentResult initial =
        runtime.execute(
            new AgentDefinition("test", "test"),
            new AgentRequest("test"),
            AgentExecutionContext.withSession(SESSION_ID));

    assertThat(initial.isSuspended()).isTrue();
    AgentProcess process = initial.process();
    assertThat(process).isNotNull();

    // Concurrent resume on SAME handle
    CyclicBarrier barrier = new CyclicBarrier(3);
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger rejectedCount = new AtomicInteger(0);

    Runnable resumeTask =
        () -> {
          try {
            barrier.await(); // Synchronize start
            AgentResult result = process.resume(new ContinuationSignal.ApprovalSignal(true, "approved"));
            successCount.incrementAndGet();
          } catch (IllegalStateException e) {
            if (e.getMessage().contains("Cannot resume process in state")) {
              rejectedCount.incrementAndGet();
            }
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        };

    Thread t1 = new Thread(resumeTask);
    Thread t2 = new Thread(resumeTask);
    Thread t3 = new Thread(resumeTask);
    t1.start();
    t2.start();
    t3.start();
    t1.join();
    t2.join();
    t3.join();

    // Assert: exactly one succeeded, others rejected at CAS
    assertThat(successCount.get()).isEqualTo(1);
    assertThat(rejectedCount.get()).isEqualTo(2);

    // Assert: backend invoked only once
    assertThat(backendResumeCount.get()).isEqualTo(1);
    assertThat(toolExecutionCount.get()).isEqualTo(1);
  }

  /**
   * Cross-runtime completion race: both execute tool, one CHECK B winner.
   *
   * <p>Proves: M5 does NOT guarantee exactly-once tool execution.
   */
  @Test
  @DisplayName("Cross-runtime completion race - one winner, one conflict")
  void crossRuntimeCompletionRace_oneWinnerOneConflict() throws Exception {
    SharedCheckpointStore store = new SharedCheckpointStore();
    SharedChatMemory memory = new SharedChatMemory();

    // Pre-create checkpoint v1 with pending toolX
    SuspensionCheckpoint cp1 =
        new SuspensionCheckpoint(
            SuspensionCheckpoint.CURRENT_SCHEMA_VERSION,
            "P100",
            1L,
            BINDING_KEY,
            SESSION_ID,
            List.of(new PendingToolCall("tc-1", "toolX", "{}")),
            List.of());
    store.create(cp1);

    // Two runtimes with independent resources
    AtomicInteger toolCountA = new AtomicInteger(0);
    AtomicInteger toolCountB = new AtomicInteger(0);
    AtomicInteger modelCountA = new AtomicInteger(0);
    AtomicInteger modelCountB = new AtomicInteger(0);

    // Synchronization to ensure both reach CHECK B
    CyclicBarrier startBarrier = new CyclicBarrier(2);
    CountDownLatch bothExecutedTools = new CountDownLatch(2);

    SpringAiToolCallingEngine engineA =
        createEngineForCompletionRace(
            store, memory, toolCountA, modelCountA, startBarrier, bothExecutedTools);
    SpringAiToolCallingEngine engineB =
        createEngineForCompletionRace(
            store, memory, toolCountB, modelCountB, startBarrier, bothExecutedTools);

    DefaultAgentRuntime runtimeA = new DefaultAgentRuntime(engineA);
    DefaultAgentRuntime runtimeB = new DefaultAgentRuntime(engineB);

    // Concurrent resume
    AtomicInteger completionCount = new AtomicInteger(0);
    AtomicInteger conflictCount = new AtomicInteger(0);
    List<Throwable> exceptions = new ArrayList<>();

    Runnable resumeA =
        () -> {
          try {
            AgentResult result =
                runtimeA.resumeProcess(
                    "P100", 1L, new ContinuationSignal.ApprovalSignal(true, "approved"));
            if (result.isCompleted()) {
              completionCount.incrementAndGet();
            }
          } catch (CheckpointTransitionConflictException e) {
            conflictCount.incrementAndGet();
            synchronized (exceptions) {
              exceptions.add(e);
            }
          } catch (Exception e) {
            synchronized (exceptions) {
              exceptions.add(e);
            }
          }
        };

    Runnable resumeB =
        () -> {
          try {
            AgentResult result =
                runtimeB.resumeProcess(
                    "P100", 1L, new ContinuationSignal.ApprovalSignal(true, "approved"));
            if (result.isCompleted()) {
              completionCount.incrementAndGet();
            }
          } catch (CheckpointTransitionConflictException e) {
            conflictCount.incrementAndGet();
            synchronized (exceptions) {
              exceptions.add(e);
            }
          } catch (Exception e) {
            synchronized (exceptions) {
              exceptions.add(e);
            }
          }
        };

    Thread ta = new Thread(resumeA);
    Thread tb = new Thread(resumeB);
    ta.start();
    tb.start();
    ta.join();
    tb.join();

    // Assert: both tools executed (NOT exactly-once)
    int totalToolExecutions = toolCountA.get() + toolCountB.get();
    assertThat(totalToolExecutions).isEqualTo(2);

    // Assert: exactly one completion, one conflict
    assertThat(completionCount.get()).isEqualTo(1);
    assertThat(conflictCount.get()).isEqualTo(1);

    // Assert: checkpoint deleted by winner
    assertThat(store.load("P100")).isEmpty();

    // Assert: only winner wrote final Assistant
    List<Message> finalMemory = memory.get(SESSION_ID);
    long assistantCount =
        finalMemory.stream().filter(m -> m instanceof AssistantMessage).count();
    assertThat(assistantCount).isEqualTo(1); // Only winner writes

    // No unexpected exceptions
    List<Throwable> unexpected =
        exceptions.stream()
            .filter(e -> !(e instanceof CheckpointTransitionConflictException))
            .toList();
    assertThat(unexpected).isEmpty();
  }

  /**
   * Cross-runtime re-suspension race: both execute tool, one CHECK B winner.
   */
  @Test
  @DisplayName("Cross-runtime re-suspension race - one winner, one conflict")
  void crossRuntimeReSuspensionRace_oneWinnerOneConflict() throws Exception {
    SharedCheckpointStore store = new SharedCheckpointStore();
    SharedChatMemory memory = new SharedChatMemory();

    // Pre-create checkpoint v1 with pending toolX
    SuspensionCheckpoint cp1 =
        new SuspensionCheckpoint(
            SuspensionCheckpoint.CURRENT_SCHEMA_VERSION,
            "P100",
            1L,
            BINDING_KEY,
            SESSION_ID,
            List.of(new PendingToolCall("tc-1", "toolX", "{}")),
            List.of());
    store.create(cp1);

    // Two runtimes - both emit new toolY requiring approval
    AtomicInteger toolCountA = new AtomicInteger(0);
    AtomicInteger toolCountB = new AtomicInteger(0);

    CyclicBarrier startBarrier = new CyclicBarrier(2);
    CountDownLatch bothExecuted = new CountDownLatch(2);

    SpringAiToolCallingEngine engineA =
        createEngineForReSuspensionRace(
            store, memory, toolCountA, startBarrier, bothExecuted);
    SpringAiToolCallingEngine engineB =
        createEngineForReSuspensionRace(
            store, memory, toolCountB, startBarrier, bothExecuted);

    DefaultAgentRuntime runtimeA = new DefaultAgentRuntime(engineA);
    DefaultAgentRuntime runtimeB = new DefaultAgentRuntime(engineB);

    // Concurrent resume
    AtomicInteger suspendedCount = new AtomicInteger(0);
    AtomicInteger conflictCount = new AtomicInteger(0);

    Runnable resumeA =
        () -> {
          try {
            AgentResult result =
                runtimeA.resumeProcess(
                    "P100", 1L, new ContinuationSignal.ApprovalSignal(true, "approved"));
            if (result.isSuspended()) {
              suspendedCount.incrementAndGet();
            }
          } catch (CheckpointTransitionConflictException e) {
            conflictCount.incrementAndGet();
          }
        };

    Runnable resumeB =
        () -> {
          try {
            AgentResult result =
                runtimeB.resumeProcess(
                    "P100", 1L, new ContinuationSignal.ApprovalSignal(true, "approved"));
            if (result.isSuspended()) {
              suspendedCount.incrementAndGet();
            }
          } catch (CheckpointTransitionConflictException e) {
            conflictCount.incrementAndGet();
          }
        };

    Thread ta = new Thread(resumeA);
    Thread tb = new Thread(resumeB);
    ta.start();
    tb.start();
    ta.join();
    tb.join();

    // Assert: both executed toolX (NOT exactly-once)
    int totalToolExecutions = toolCountA.get() + toolCountB.get();
    assertThat(totalToolExecutions).isEqualTo(2);

    // Assert: exactly one suspended, one conflict
    assertThat(suspendedCount.get()).isEqualTo(1);
    assertThat(conflictCount.get()).isEqualTo(1);

    // Assert: store contains exactly one v2
    SuspensionCheckpoint stored = store.load("P100").orElseThrow();
    assertThat(stored.checkpointVersion()).isEqualTo(2L);
    assertThat(stored.processId()).isEqualTo("P100");
  }

  /**
   * Stale resume after race winner advances → rejected at CHECK A.
   */
  @Test
  @DisplayName("Stale resume after race - rejected before side effects")
  void staleResumeAfterRace_rejectedBeforeSideEffects() throws Exception {
    SharedCheckpointStore store = new SharedCheckpointStore();
    SharedChatMemory memory = new SharedChatMemory();

    // Initial checkpoint v1
    SuspensionCheckpoint cp1 =
        new SuspensionCheckpoint(
            SuspensionCheckpoint.CURRENT_SCHEMA_VERSION,
            "P100",
            1L,
            BINDING_KEY,
            SESSION_ID,
            List.of(new PendingToolCall("tc-1", "toolX", "{}")),
            List.of());
    store.create(cp1);

    // Runtime A wins race: v1 → v2
    AtomicInteger toolCountA = new AtomicInteger(0);
    SpringAiToolCallingEngine engineA =
        new SpringAiToolCallingEngine(
            createModelEmittingToolY(new AtomicInteger()),
            List.of(
                createTool("toolX", toolCountA), createTool("toolY", new AtomicInteger())),
            memory,
            (t, a, c) ->
                "toolY".equals(t) ? GovernanceDecision.REQUIRE_APPROVAL : GovernanceDecision.ALLOW,
            store,
            createResolver(new AtomicInteger()),
            BINDING_KEY);

    DefaultAgentRuntime runtimeA = new DefaultAgentRuntime(engineA);
    AgentResult resultA =
        runtimeA.resumeProcess(
            "P100", 1L, new ContinuationSignal.ApprovalSignal(true, "approved"));

    assertThat(resultA.isSuspended()).isTrue();
    assertThat(store.load("P100").orElseThrow().checkpointVersion()).isEqualTo(2L);

    // Runtime B attempts stale v1 resume
    AtomicInteger toolCountB = new AtomicInteger(0);
    AtomicInteger modelCountB = new AtomicInteger(0);
    AtomicInteger resolverCountB = new AtomicInteger(0);

    SpringAiToolCallingEngine engineB =
        new SpringAiToolCallingEngine(
            createCompletingModel(modelCountB),
            List.of(createTool("toolX", toolCountB)),
            memory,
            (t, a, c) -> GovernanceDecision.ALLOW,
            store,
            createResolver(resolverCountB),
            BINDING_KEY);

    DefaultAgentRuntime runtimeB = new DefaultAgentRuntime(engineB);

    // Attempt stale resume
    assertThatThrownBy(
            () ->
                runtimeB.resumeProcess(
                    "P100", 1L, new ContinuationSignal.ApprovalSignal(true, "approved")))
        .isInstanceOf(StaleCheckpointException.class);

    // Assert: zero side effects (CHECK A prevented them)
    assertThat(toolCountB.get()).isZero();
    assertThat(modelCountB.get()).isZero();
    assertThat(resolverCountB.get()).isZero();

    // Assert: checkpoint v2 unchanged
    assertThat(store.load("P100").orElseThrow().checkpointVersion()).isEqualTo(2L);
  }

  /**
   * Local handle after CHECK B conflict → FAILED (Phase 6 semantics).
   */
  @Test
  @DisplayName("Local handle after CHECK B conflict becomes FAILED")
  void localHandle_afterCheckBConflict_becomesFailed() throws Exception {
    SharedCheckpointStore store = new SharedCheckpointStore();
    SharedChatMemory memory = new SharedChatMemory();

    // Runtime A: create initial suspended process
    ChatModel modelRequiringApproval = new ChatModel() {
      @Override
      public ChatResponse call(org.springframework.ai.chat.prompt.Prompt prompt) {
        return new ChatResponse(
            List.of(
                new Generation(
                    AssistantMessage.builder()
                        .content("Need toolX")
                        .toolCalls(
                            List.of(
                                new AssistantMessage.ToolCall(
                                    "tc-1", "function", "toolX", "{}")))
                        .build())));
      }

      @Override
      public org.springframework.ai.chat.prompt.ChatOptions getOptions() {
        return org.springframework.ai.model.tool.ToolCallingChatOptions.builder().build();
      }
    };

    SpringAiToolCallingEngine engineA =
        new SpringAiToolCallingEngine(
            modelRequiringApproval,
            List.of(createTool("toolX", new AtomicInteger())),
            memory,
            (t, a, c) -> GovernanceDecision.REQUIRE_APPROVAL,
            store,
            createResolver(new AtomicInteger()),
            BINDING_KEY);

    DefaultAgentRuntime runtimeA = new DefaultAgentRuntime(engineA);

    // Get suspended process handle
    AgentResult initialA =
        runtimeA.execute(
            new AgentDefinition("test", "test"),
            new AgentRequest("test"),
            AgentExecutionContext.withSession(SESSION_ID));

    assertThat(initialA.isSuspended()).isTrue();
    AgentProcess handleA = initialA.process();
    assertThat(handleA).isNotNull();

    // Runtime B completes successfully
    SpringAiToolCallingEngine engineB =
        new SpringAiToolCallingEngine(
            createCompletingModel(new AtomicInteger()),
            List.of(createTool("toolX", new AtomicInteger())),
            memory,
            (t, a, c) -> GovernanceDecision.ALLOW,
            store,
            createResolver(new AtomicInteger()),
            BINDING_KEY);

    DefaultAgentRuntime runtimeB = new DefaultAgentRuntime(engineB);
    AgentResult resultB =
        runtimeB.resumeProcess(
            handleA.id(), 1L, new ContinuationSignal.ApprovalSignal(true, "approved"));

    assertThat(resultB.isCompleted()).isTrue();

    // Runtime A's local handle attempts resume → checkpoint not found (B deleted it)
    assertThatThrownBy(
            () -> handleA.resume(new ContinuationSignal.ApprovalSignal(true, "approved")))
        .isInstanceOf(cn.bitcss.arctra.checkpoint.CheckpointNotFoundException.class);

    // Assert: local handle is FAILED (Phase 6 semantics)
    assertThat(handleA.status()).isEqualTo(ProcessStatus.FAILED);
  }

  // ========== HELPER METHODS ==========

  private SpringAiToolCallingEngine createEngineForCompletionRace(
      CheckpointStore store,
      ChatMemory memory,
      AtomicInteger toolCount,
      AtomicInteger modelCount,
      CyclicBarrier startBarrier,
      CountDownLatch bothExecutedTools) {

    ToolCallback tool =
        new ToolCallback() {
          @Override
          public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder()
                .name("toolX")
                .description("toolX")
                .inputSchema("{\"type\":\"object\",\"properties\":{}}")
                .build();
          }

          @Override
          public String call(String functionArguments) {
            toolCount.incrementAndGet();
            bothExecutedTools.countDown();
            // Wait for both to execute before returning
            try {
              bothExecutedTools.await();
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
            return "toolX result";
          }
        };

    ChatModel model =
        new ChatModel() {
          @Override
          public ChatResponse call(org.springframework.ai.chat.prompt.Prompt prompt) {
            modelCount.incrementAndGet();
            return new ChatResponse(
                List.of(new Generation(new AssistantMessage("Final answer"))));
          }

          @Override
          public org.springframework.ai.chat.prompt.ChatOptions getOptions() {
            return org.springframework.ai.model.tool.ToolCallingChatOptions.builder().build();
          }
        };

    return new SpringAiToolCallingEngine(
        model,
        List.of(tool),
        memory,
        (t, a, c) -> GovernanceDecision.ALLOW,
        store,
        createResolver(new AtomicInteger()),
        BINDING_KEY);
  }

  private SpringAiToolCallingEngine createEngineForReSuspensionRace(
      CheckpointStore store,
      ChatMemory memory,
      AtomicInteger toolCount,
      CyclicBarrier startBarrier,
      CountDownLatch bothExecuted) {

    ToolCallback toolX =
        new ToolCallback() {
          @Override
          public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder()
                .name("toolX")
                .description("toolX")
                .inputSchema("{\"type\":\"object\",\"properties\":{}}")
                .build();
          }

          @Override
          public String call(String functionArguments) {
            toolCount.incrementAndGet();
            bothExecuted.countDown();
            try {
              bothExecuted.await();
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
            return "toolX result";
          }
        };

    ToolCallback toolY = createTool("toolY", new AtomicInteger());

    ChatModel model = createModelEmittingToolY(new AtomicInteger());

    return new SpringAiToolCallingEngine(
        model,
        List.of(toolX, toolY),
        memory,
        (t, a, c) ->
            "toolY".equals(t) ? GovernanceDecision.REQUIRE_APPROVAL : GovernanceDecision.ALLOW,
        store,
        createResolver(new AtomicInteger()),
        BINDING_KEY);
  }

  private ToolCallback createTool(String name, AtomicInteger executionCount) {
    return new ToolCallback() {
      @Override
      public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
            .name(name)
            .description(name)
            .inputSchema("{\"type\":\"object\",\"properties\":{}}")
            .build();
      }

      @Override
      public String call(String functionArguments) {
        executionCount.incrementAndGet();
        return name + " result";
      }
    };
  }

  private ChatModel createCompletingModel(AtomicInteger callCount) {
    return new ChatModel() {
      @Override
      public ChatResponse call(org.springframework.ai.chat.prompt.Prompt prompt) {
        callCount.incrementAndGet();
        return new ChatResponse(List.of(new Generation(new AssistantMessage("Final answer"))));
      }

      @Override
      public org.springframework.ai.chat.prompt.ChatOptions getOptions() {
        return org.springframework.ai.model.tool.ToolCallingChatOptions.builder().build();
      }
    };
  }

  private ChatModel createModelEmittingToolY(AtomicInteger callCount) {
    return new ChatModel() {
      @Override
      public ChatResponse call(org.springframework.ai.chat.prompt.Prompt prompt) {
        callCount.incrementAndGet();
        return new ChatResponse(
            List.of(
                new Generation(
                    AssistantMessage.builder()
                        .content("Need toolY")
                        .toolCalls(
                            List.of(
                                new AssistantMessage.ToolCall(
                                    "tc-2", "function", "toolY", "{}")))
                        .build())));
      }

      @Override
      public org.springframework.ai.chat.prompt.ChatOptions getOptions() {
        return org.springframework.ai.model.tool.ToolCallingChatOptions.builder().build();
      }
    };
  }

  private RuntimeBindingResolver createResolver(AtomicInteger callCount) {
    return (processId, runtimeBindingKey, sessionId) -> {
      callCount.incrementAndGet();
      return new RuntimeBinding(
          new AgentDefinition("test", "test"),
          AgentExecutionContext.withSession(sessionId));
    };
  }

  // ========== SHARED INFRASTRUCTURE ==========

  private static class SharedCheckpointStore implements CheckpointStore {
    private final Map<String, SuspensionCheckpoint> store = new ConcurrentHashMap<>();

    @Override
    public void create(SuspensionCheckpoint checkpoint) {
      store.put(checkpoint.processId(), checkpoint);
    }

    @Override
    public Optional<SuspensionCheckpoint> load(String processId) {
      return Optional.ofNullable(store.get(processId));
    }

    @Override
    public boolean replaceIfVersion(
        String processId, long expectedVersion, SuspensionCheckpoint replacement) {
      boolean[] replaced = {false};
      store.compute(
          processId,
          (key, current) -> {
            if (current != null && current.checkpointVersion() == expectedVersion) {
              replaced[0] = true;
              return replacement;
            }
            replaced[0] = false;
            return current;
          });
      return replaced[0];
    }

    @Override
    public boolean deleteIfVersion(String processId, long expectedVersion) {
      boolean[] deleted = {false};
      store.compute(
          processId,
          (key, current) -> {
            if (current != null && current.checkpointVersion() == expectedVersion) {
              deleted[0] = true;
              return null;
            }
            deleted[0] = false;
            return current;
          });
      return deleted[0];
    }
  }

  private static class SharedChatMemory implements ChatMemory {
    private final Map<String, List<Message>> memory = new ConcurrentHashMap<>();

    @Override
    public void add(String conversationId, List<Message> messages) {
      memory.computeIfAbsent(conversationId, k -> new ArrayList<>()).addAll(messages);
    }

    @Override
    public void add(String conversationId, Message message) {
      memory.computeIfAbsent(conversationId, k -> new ArrayList<>()).add(message);
    }

    @Override
    public List<Message> get(String conversationId) {
      return new ArrayList<>(memory.getOrDefault(conversationId, List.of()));
    }

    @Override
    public void clear(String conversationId) {
      memory.remove(conversationId);
    }
  }
}
