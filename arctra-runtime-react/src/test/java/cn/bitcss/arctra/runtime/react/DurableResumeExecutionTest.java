package cn.bitcss.arctra.runtime.react;

import static org.junit.jupiter.api.Assertions.*;

import cn.bitcss.arctra.agent.AgentDefinition;
import cn.bitcss.arctra.agent.AgentExecutionContext;
import cn.bitcss.arctra.agent.AgentResult;
import cn.bitcss.arctra.checkpoint.CheckpointNotFoundException;
import cn.bitcss.arctra.checkpoint.CheckpointStore;
import cn.bitcss.arctra.checkpoint.CheckpointTransitionConflictException;
import cn.bitcss.arctra.checkpoint.PendingToolCall;
import cn.bitcss.arctra.checkpoint.StaleCheckpointException;
import cn.bitcss.arctra.checkpoint.SuspensionCheckpoint;
import cn.bitcss.arctra.governance.GovernanceDecision;
import cn.bitcss.arctra.governance.ToolGovernancePolicy;
import cn.bitcss.arctra.process.ContinuationSignal;
import cn.bitcss.arctra.runtime.ResumePreparationException;
import cn.bitcss.arctra.runtime.RuntimeBinding;
import cn.bitcss.arctra.runtime.RuntimeBindingException;
import cn.bitcss.arctra.runtime.RuntimeBindingResolver;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * Tests for Phase 5 durable resume execution.
 *
 * @author lov3r
 * @since M5-T4
 */
class DurableResumeExecutionTest {

  /**
   * Invariant #1: Missing checkpoint prevents all side effects (CHECK A).
   */
  @Test
  void missingCheckpoint_throwsCheckpointNotFoundException() {
    CountingCheckpointStore store = new CountingCheckpointStore();
    CountingRuntimeBindingResolver resolver = new CountingRuntimeBindingResolver();
    CountingChatModel model = new CountingChatModel();
    CountingToolCallback tool = new CountingToolCallback();
    CountingGovernancePolicy governance = new CountingGovernancePolicy();
    CountingChatMemory memory = new CountingChatMemory();

    SpringAiToolCallingEngine engine =
        new SpringAiToolCallingEngine(
            model,
            List.of(tool),
            memory,
            governance,
            store,
            resolver,
            "test-key");

    assertThrows(
        CheckpointNotFoundException.class,
        () ->
            engine.resumeProcess(
                "P-missing", 1L, new ContinuationSignal.ApprovalSignal(true, "test")));

    // CHECK A: Verify ZERO side effects before checkpoint validation
    assertEquals(1, store.loadCount.get(), "Checkpoint load must be attempted");
    assertEquals(0, resolver.resolveCount.get(), "Resolver must NOT be called");
    assertEquals(0, tool.executionCount.get(), "Tool must NOT be executed");
    assertEquals(0, model.callCount.get(), "Model must NOT be called");
    assertEquals(0, governance.evaluationCount.get(), "Governance must NOT be evaluated");
    assertEquals(0, memory.writeCount.get(), "ChatMemory must NOT be written");
  }

  /**
   * Invariant #2: Stale checkpoint prevents all side effects (CHECK A).
   */
  @Test
  void staleVersion_throwsStaleCheckpointException() {
    CountingCheckpointStore store = new CountingCheckpointStore();
    SuspensionCheckpoint checkpoint = createCheckpoint("P-100", 2L, "test-key", null);
    store.checkpoints.put("P-100", checkpoint);

    CountingRuntimeBindingResolver resolver = new CountingRuntimeBindingResolver();
    CountingChatModel model = new CountingChatModel();
    CountingToolCallback tool = new CountingToolCallback();
    CountingGovernancePolicy governance = new CountingGovernancePolicy();
    CountingChatMemory memory = new CountingChatMemory();

    SpringAiToolCallingEngine engine =
        new SpringAiToolCallingEngine(
            model,
            List.of(tool),
            memory,
            governance,
            store,
            resolver,
            "test-key");

    assertThrows(
        StaleCheckpointException.class,
        () ->
            engine.resumeProcess(
                "P-100", 1L, new ContinuationSignal.ApprovalSignal(true, "test")));

    // CHECK A: Verify ZERO side effects after version mismatch
    assertEquals(1, store.loadCount.get(), "Checkpoint load must be attempted");
    assertEquals(0, resolver.resolveCount.get(), "Resolver must NOT be called");
    assertEquals(0, tool.executionCount.get(), "Tool must NOT be executed");
    assertEquals(0, model.callCount.get(), "Model must NOT be called");
    assertEquals(0, governance.evaluationCount.get(), "Governance must NOT be evaluated");
    assertEquals(0, memory.writeCount.get(), "ChatMemory must NOT be written");
  }

  @Test
  void resolverUsesCheckpointKey() {
    FakeCheckpointStore store = new FakeCheckpointStore();
    SuspensionCheckpoint checkpoint =
        createCheckpoint("P-100", 1L, "incident-agent", "session-1");
    store.checkpoints.put("P-100", checkpoint);

    FakeRuntimeBindingResolver resolver = new FakeRuntimeBindingResolver();

    SpringAiToolCallingEngine engine =
        new SpringAiToolCallingEngine(
            completionChatModel(),
            List.of(fakeToolCallback()),
            chatMemory(),
            allowAllPolicy(),
            store,
            resolver,
            "runtime-B");

    engine.resumeProcess("P-100", 1L, new ContinuationSignal.ApprovalSignal(true, "test"));

    assertEquals("incident-agent", resolver.lastResolvedKey);
    assertNotEquals("runtime-B", resolver.lastResolvedKey);
  }

  /**
   * Invariant #3: Resolver failure prevents downstream side effects.
   */
  @Test
  void resolverFailure_throwsResumePreparationException() {
    CountingCheckpointStore store = new CountingCheckpointStore();
    SuspensionCheckpoint checkpoint = createCheckpoint("P-100", 1L, "test-key", null);
    store.checkpoints.put("P-100", checkpoint);

    CountingRuntimeBindingResolver throwingResolver =
        new CountingRuntimeBindingResolver() {
          @Override
          public RuntimeBinding resolve(
              String processId, String runtimeBindingKey, String sessionId) {
            resolveCount.incrementAndGet();
            throw new RuntimeBindingException(runtimeBindingKey, "Resolver failure");
          }
        };

    CountingChatModel model = new CountingChatModel();
    CountingToolCallback tool = new CountingToolCallback();
    CountingGovernancePolicy governance = new CountingGovernancePolicy();
    CountingChatMemory memory = new CountingChatMemory();

    SpringAiToolCallingEngine engine =
        new SpringAiToolCallingEngine(
            model,
            List.of(tool),
            memory,
            governance,
            store,
            throwingResolver,
            "test-key");

    assertThrows(
        ResumePreparationException.class,
        () ->
            engine.resumeProcess(
                "P-100", 1L, new ContinuationSignal.ApprovalSignal(true, "test")));

    // Verify: Resolver called, but downstream operations prevented
    assertEquals(1, store.loadCount.get(), "Checkpoint load must succeed");
    assertEquals(1, throwingResolver.resolveCount.get(), "Resolver must be called");
    assertEquals(0, tool.executionCount.get(), "Tool must NOT be executed after resolver failure");
    assertEquals(0, model.callCount.get(), "Model must NOT be called after resolver failure");
    assertEquals(
        0,
        governance.evaluationCount.get(),
        "Governance must NOT be evaluated after resolver failure");
    assertEquals(
        0, memory.writeCount.get(), "ChatMemory must NOT be written after resolver failure");
  }

  @Test
  void completionCheckB_success_deletesCheckpoint() {
    FakeCheckpointStore store = new FakeCheckpointStore();
    SuspensionCheckpoint checkpoint = createCheckpoint("P-100", 1L, "test-key", null);
    store.checkpoints.put("P-100", checkpoint);

    FakeRuntimeBindingResolver resolver = new FakeRuntimeBindingResolver();

    SpringAiToolCallingEngine engine =
        new SpringAiToolCallingEngine(
            completionChatModel(),
            List.of(fakeToolCallback()),
            chatMemory(),
            allowAllPolicy(),
            store,
            resolver,
            "test-key");

    AgentResult result =
        engine.resumeProcess("P-100", 1L, new ContinuationSignal.ApprovalSignal(true, "test"));

    assertTrue(result.isCompleted());
    assertFalse(store.checkpoints.containsKey("P-100"));
  }

  @Test
  void completionCheckB_conflict_throws() {
    FakeCheckpointStore store =
        new FakeCheckpointStore() {
          @Override
          public boolean deleteIfVersion(String processId, long expectedVersion) {
            return false;
          }
        };

    SuspensionCheckpoint checkpoint = createCheckpoint("P-100", 1L, "test-key", null);
    store.checkpoints.put("P-100", checkpoint);

    FakeRuntimeBindingResolver resolver = new FakeRuntimeBindingResolver();

    SpringAiToolCallingEngine engine =
        new SpringAiToolCallingEngine(
            completionChatModel(),
            List.of(fakeToolCallback()),
            chatMemory(),
            allowAllPolicy(),
            store,
            resolver,
            "test-key");

    assertThrows(
        CheckpointTransitionConflictException.class,
        () ->
            engine.resumeProcess(
                "P-100", 1L, new ContinuationSignal.ApprovalSignal(true, "test")));
  }

  @Test
  void reSuspension_createsCheckpointV2() {
    FakeCheckpointStore store = new FakeCheckpointStore();
    SuspensionCheckpoint checkpoint = createCheckpoint("P-100", 1L, "test-key", null);
    store.checkpoints.put("P-100", checkpoint);

    FakeRuntimeBindingResolver resolver = new FakeRuntimeBindingResolver();

    SpringAiToolCallingEngine engine =
        new SpringAiToolCallingEngine(
            requireApprovalChatModel(),
            List.of(fakeToolCallback()),
            chatMemory(),
            requireApprovalPolicy(),
            store,
            resolver,
            "test-key");

    AgentResult result =
        engine.resumeProcess("P-100", 1L, new ContinuationSignal.ApprovalSignal(true, "test"));

    assertTrue(result.isSuspended());
    SuspensionCheckpoint v2 = store.checkpoints.get("P-100");
    assertNotNull(v2);
    assertEquals(2L, v2.checkpointVersion());
    assertEquals("P-100", v2.processId());
    assertEquals("test-key", v2.runtimeBindingKey());
  }

  @Test
  void reSuspension_preservesProcessId() {
    FakeCheckpointStore store = new FakeCheckpointStore();
    SuspensionCheckpoint checkpoint = createCheckpoint("P-100", 1L, "test-key", null);
    store.checkpoints.put("P-100", checkpoint);

    FakeRuntimeBindingResolver resolver = new FakeRuntimeBindingResolver();

    SpringAiToolCallingEngine engine =
        new SpringAiToolCallingEngine(
            requireApprovalChatModel(),
            List.of(fakeToolCallback()),
            chatMemory(),
            requireApprovalPolicy(),
            store,
            resolver,
            "test-key");

    AgentResult result =
        engine.resumeProcess("P-100", 1L, new ContinuationSignal.ApprovalSignal(true, "test"));

    assertEquals("P-100", result.process().id());
  }

  @Test
  void reSuspension_preservesRuntimeBindingKey() {
    FakeCheckpointStore store = new FakeCheckpointStore();
    SuspensionCheckpoint checkpoint =
        createCheckpoint("P-100", 1L, "incident-agent", "session-1");
    store.checkpoints.put("P-100", checkpoint);

    FakeRuntimeBindingResolver resolver = new FakeRuntimeBindingResolver();

    SpringAiToolCallingEngine engine =
        new SpringAiToolCallingEngine(
            requireApprovalChatModel(),
            List.of(fakeToolCallback()),
            chatMemory(),
            requireApprovalPolicy(),
            store,
            resolver,
            "runtime-B"); // Different key

    engine.resumeProcess("P-100", 1L, new ContinuationSignal.ApprovalSignal(true, "test"));

    SuspensionCheckpoint v2 = store.checkpoints.get("P-100");
    assertEquals("incident-agent", v2.runtimeBindingKey());
    assertNotEquals("runtime-B", v2.runtimeBindingKey());
  }

  @Test
  void reSuspensionCheckB_conflict_throws() {
    FakeCheckpointStore store =
        new FakeCheckpointStore() {
          @Override
          public boolean replaceIfVersion(
              String processId, long expectedVersion, SuspensionCheckpoint replacement) {
            return false;
          }
        };

    SuspensionCheckpoint checkpoint = createCheckpoint("P-100", 1L, "test-key", null);
    store.checkpoints.put("P-100", checkpoint);

    FakeRuntimeBindingResolver resolver = new FakeRuntimeBindingResolver();

    SpringAiToolCallingEngine engine =
        new SpringAiToolCallingEngine(
            requireApprovalChatModel(),
            List.of(fakeToolCallback()),
            chatMemory(),
            requireApprovalPolicy(),
            store,
            resolver,
            "test-key");

    assertThrows(
        CheckpointTransitionConflictException.class,
        () ->
            engine.resumeProcess(
                "P-100", 1L, new ContinuationSignal.ApprovalSignal(true, "test")));
  }

  /**
   * Invariant #20: Ephemeral-configured engine rejects durable resume.
   */
  @Test
  void ephemeralConfig_durableResumeRejected() {
    // Construct engine with 4-parameter constructor (ephemeral-only)
    SpringAiToolCallingEngine engine =
        new SpringAiToolCallingEngine(
            completionChatModel(), List.of(fakeToolCallback()), chatMemory(), allowAllPolicy());

    // Attempt durable resume
    IllegalStateException exception =
        assertThrows(
            IllegalStateException.class,
            () ->
                engine.resumeProcess(
                    "P-100", 1L, new ContinuationSignal.ApprovalSignal(true, "test")));

    // Verify error message mentions configuration
    assertTrue(
        exception.getMessage().contains("durable configuration"),
        "Exception must mention durable configuration requirement");
  }

  private SuspensionCheckpoint createCheckpoint(
      String processId, long version, String bindingKey, String sessionId) {
    return new SuspensionCheckpoint(
        SuspensionCheckpoint.CURRENT_SCHEMA_VERSION,
        processId,
        version,
        bindingKey,
        sessionId,
        List.of(new PendingToolCall("tc-1", "testTool", "{}")),
        List.of());
  }

  private ChatMemory chatMemory() {
    return MessageWindowChatMemory.builder().build();
  }

  private ChatModel completionChatModel() {
    return new ChatModel() {
      @Override
      public ChatResponse call(org.springframework.ai.chat.prompt.Prompt prompt) {
        return new ChatResponse(
            List.of(
                new Generation(
                    new org.springframework.ai.chat.messages.AssistantMessage("Done"))));
      }

      @Override
      public org.springframework.ai.chat.prompt.ChatOptions getOptions() {
        return org.springframework.ai.model.tool.ToolCallingChatOptions.builder().build();
      }
    };
  }

  private ToolGovernancePolicy allowAllPolicy() {
    return (toolName, arguments, context) -> GovernanceDecision.ALLOW;
  }

  private ToolGovernancePolicy requireApprovalPolicy() {
    return (toolName, arguments, context) -> GovernanceDecision.REQUIRE_APPROVAL;
  }

  private ChatModel requireApprovalChatModel() {
    return new ChatModel() {
      @Override
      public ChatResponse call(org.springframework.ai.chat.prompt.Prompt prompt) {
        return new ChatResponse(
            List.of(
                new Generation(
                    org.springframework.ai.chat.messages.AssistantMessage.builder()
                        .content("Need approval")
                        .toolCalls(
                            List.of(
                                new org.springframework.ai.chat.messages.AssistantMessage.ToolCall(
                                    "tc-new", "function", "testTool", "{}")))
                        .build())));
      }

      @Override
      public org.springframework.ai.chat.prompt.ChatOptions getOptions() {
        return org.springframework.ai.model.tool.ToolCallingChatOptions.builder().build();
      }
    };
  }

  private ToolCallback fakeToolCallback() {
    return new ToolCallback() {
      @Override
      public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
            .name("testTool")
            .description("test")
            .inputSchema("{\"type\":\"object\",\"properties\":{}}")
            .build();
      }

      @Override
      public String call(String functionArguments) {
        return "result";
      }
    };
  }

  private static class FakeCheckpointStore implements CheckpointStore {
    final Map<String, SuspensionCheckpoint> checkpoints = new ConcurrentHashMap<>();

    @Override
    public void create(SuspensionCheckpoint checkpoint) {
      checkpoints.put(checkpoint.processId(), checkpoint);
    }

    @Override
    public Optional<SuspensionCheckpoint> load(String processId) {
      return Optional.ofNullable(checkpoints.get(processId));
    }

    @Override
    public boolean replaceIfVersion(
        String processId, long expectedVersion, SuspensionCheckpoint replacement) {
      SuspensionCheckpoint current = checkpoints.get(processId);
      if (current != null && current.checkpointVersion() == expectedVersion) {
        checkpoints.put(processId, replacement);
        return true;
      }
      return false;
    }

    @Override
    public boolean deleteIfVersion(String processId, long expectedVersion) {
      SuspensionCheckpoint current = checkpoints.get(processId);
      if (current != null && current.checkpointVersion() == expectedVersion) {
        checkpoints.remove(processId);
        return true;
      }
      return false;
    }
  }

  private static class FakeRuntimeBindingResolver implements RuntimeBindingResolver {
    String lastResolvedKey = null;

    @Override
    public RuntimeBinding resolve(String processId, String runtimeBindingKey, String sessionId) {
      lastResolvedKey = runtimeBindingKey;
      return new RuntimeBinding(
          new AgentDefinition("test", "test"),
          AgentExecutionContext.withSession(sessionId != null ? sessionId : "test-session"));
    }
  }

  // Counting helpers for CHECK A verification

  private static class CountingCheckpointStore implements CheckpointStore {
    final Map<String, SuspensionCheckpoint> checkpoints = new ConcurrentHashMap<>();
    final java.util.concurrent.atomic.AtomicInteger loadCount =
        new java.util.concurrent.atomic.AtomicInteger(0);

    @Override
    public void create(SuspensionCheckpoint checkpoint) {
      checkpoints.put(checkpoint.processId(), checkpoint);
    }

    @Override
    public Optional<SuspensionCheckpoint> load(String processId) {
      loadCount.incrementAndGet();
      return Optional.ofNullable(checkpoints.get(processId));
    }

    @Override
    public boolean replaceIfVersion(
        String processId, long expectedVersion, SuspensionCheckpoint replacement) {
      SuspensionCheckpoint current = checkpoints.get(processId);
      if (current != null && current.checkpointVersion() == expectedVersion) {
        checkpoints.put(processId, replacement);
        return true;
      }
      return false;
    }

    @Override
    public boolean deleteIfVersion(String processId, long expectedVersion) {
      SuspensionCheckpoint current = checkpoints.get(processId);
      if (current != null && current.checkpointVersion() == expectedVersion) {
        checkpoints.remove(processId);
        return true;
      }
      return false;
    }
  }

  private static class CountingRuntimeBindingResolver implements RuntimeBindingResolver {
    final java.util.concurrent.atomic.AtomicInteger resolveCount =
        new java.util.concurrent.atomic.AtomicInteger(0);

    @Override
    public RuntimeBinding resolve(String processId, String runtimeBindingKey, String sessionId) {
      resolveCount.incrementAndGet();
      return new RuntimeBinding(
          new AgentDefinition("test", "test"),
          AgentExecutionContext.withSession(sessionId != null ? sessionId : "test-session"));
    }
  }

  private static class CountingChatModel implements ChatModel {
    final java.util.concurrent.atomic.AtomicInteger callCount =
        new java.util.concurrent.atomic.AtomicInteger(0);

    @Override
    public ChatResponse call(org.springframework.ai.chat.prompt.Prompt prompt) {
      callCount.incrementAndGet();
      return new ChatResponse(
          List.of(
              new Generation(
                  new org.springframework.ai.chat.messages.AssistantMessage("Done"))));
    }

    @Override
    public org.springframework.ai.chat.prompt.ChatOptions getOptions() {
      return org.springframework.ai.model.tool.ToolCallingChatOptions.builder().build();
    }
  }

  private static class CountingToolCallback implements ToolCallback {
    final java.util.concurrent.atomic.AtomicInteger executionCount =
        new java.util.concurrent.atomic.AtomicInteger(0);

    @Override
    public ToolDefinition getToolDefinition() {
      return ToolDefinition.builder()
          .name("testTool")
          .description("test")
          .inputSchema("{\"type\":\"object\",\"properties\":{}}")
          .build();
    }

    @Override
    public String call(String functionArguments) {
      executionCount.incrementAndGet();
      return "result";
    }
  }

  private static class CountingGovernancePolicy implements ToolGovernancePolicy {
    final java.util.concurrent.atomic.AtomicInteger evaluationCount =
        new java.util.concurrent.atomic.AtomicInteger(0);

    @Override
    public GovernanceDecision evaluate(
        String toolName, String arguments, AgentExecutionContext context) {
      evaluationCount.incrementAndGet();
      return GovernanceDecision.ALLOW;
    }
  }

  private static class CountingChatMemory implements ChatMemory {
    final java.util.concurrent.atomic.AtomicInteger writeCount =
        new java.util.concurrent.atomic.AtomicInteger(0);
    private final ChatMemory delegate = MessageWindowChatMemory.builder().build();

    @Override
    public void add(String conversationId, List<org.springframework.ai.chat.messages.Message> messages) {
      writeCount.incrementAndGet();
      delegate.add(conversationId, messages);
    }

    @Override
    public void add(String conversationId, org.springframework.ai.chat.messages.Message message) {
      writeCount.incrementAndGet();
      delegate.add(conversationId, message);
    }

    @Override
    public List<org.springframework.ai.chat.messages.Message> get(String conversationId) {
      return delegate.get(conversationId);
    }

    @Override
    public void clear(String conversationId) {
      delegate.clear(conversationId);
    }
  }
}
