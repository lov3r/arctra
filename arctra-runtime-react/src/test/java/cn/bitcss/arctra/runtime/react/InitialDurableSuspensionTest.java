package cn.bitcss.arctra.runtime.react;

import static org.junit.jupiter.api.Assertions.*;

import cn.bitcss.arctra.agent.AgentDefinition;
import cn.bitcss.arctra.agent.AgentExecutionContext;
import cn.bitcss.arctra.agent.AgentRequest;
import cn.bitcss.arctra.agent.AgentResult;
import cn.bitcss.arctra.checkpoint.CheckpointStore;
import cn.bitcss.arctra.checkpoint.SuspensionCheckpoint;
import cn.bitcss.arctra.governance.GovernanceDecision;
import cn.bitcss.arctra.governance.ToolGovernancePolicy;
import cn.bitcss.arctra.process.ProcessStatus;
import cn.bitcss.arctra.runtime.RuntimeBinding;
import cn.bitcss.arctra.runtime.RuntimeBindingResolver;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * Tests for Phase 4 initial durable suspension.
 *
 * @author lov3r
 * @since M5-T4
 */
class InitialDurableSuspensionTest {

  @Test
  void durableConfig_allPresent_accepted() {
    assertDoesNotThrow(
        () ->
            new SpringAiToolCallingEngine(
                fakeChatModel(),
                java.util.List.of(),
                chatMemory(),
                alwaysRequireApprovalPolicy(),
                new FakeCheckpointStore(),
                new FakeRuntimeBindingResolver(),
                "test-key"));
  }

  @Test
  void durableConfig_allAbsent_accepted() {
    assertDoesNotThrow(
        () ->
            new SpringAiToolCallingEngine(
                fakeChatModel(),
                java.util.List.of(),
                chatMemory(),
                alwaysRequireApprovalPolicy(),
                null,
                null,
                null));
  }

  @Test
  void durableConfig_partialStoreOnly_rejected() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SpringAiToolCallingEngine(
                fakeChatModel(),
                java.util.List.of(),
                chatMemory(),
                alwaysRequireApprovalPolicy(),
                new FakeCheckpointStore(),
                null,
                null));
  }

  @Test
  void durableMode_requireApproval_createsCheckpointV1() {
    FakeCheckpointStore store = new FakeCheckpointStore();

    SpringAiToolCallingEngine engine =
        new SpringAiToolCallingEngine(
            requireApprovalChatModel(),
            java.util.List.of(fakeToolCallback()),
            chatMemory(),
            alwaysRequireApprovalPolicy(),
            store,
            new FakeRuntimeBindingResolver(),
            "incident-agent");

    AgentResult result =
        engine.execute(
            new AgentDefinition("test", "test"),
            new AgentRequest("test"),
            AgentExecutionContext.withSession("session-123"));

    assertTrue(result.isSuspended());
    assertEquals(1, store.checkpoints.size());
    SuspensionCheckpoint cp = store.checkpoints.values().iterator().next();
    assertEquals(1L, cp.checkpointVersion());
    assertEquals("incident-agent", cp.runtimeBindingKey());
    assertEquals("session-123", cp.sessionId());
  }

  @Test
  void durableMode_processIdMatchesCheckpoint() {
    FakeCheckpointStore store = new FakeCheckpointStore();

    SpringAiToolCallingEngine engine =
        new SpringAiToolCallingEngine(
            requireApprovalChatModel(),
            java.util.List.of(fakeToolCallback()),
            chatMemory(),
            alwaysRequireApprovalPolicy(),
            store,
            new FakeRuntimeBindingResolver(),
            "test-key");

    AgentResult result =
        engine.execute(
            new AgentDefinition("test", "test"),
            new AgentRequest("test"),
            AgentExecutionContext.stateless());

    String processId = result.process().id();
    SuspensionCheckpoint cp = store.checkpoints.get(processId);
    assertNotNull(cp);
    assertEquals(processId, cp.processId());
  }

  @Test
  void durableMode_storeFailure_noProcessExposed() {
    FakeCheckpointStore throwingStore =
        new FakeCheckpointStore() {
          @Override
          public void create(SuspensionCheckpoint checkpoint) {
            throw new RuntimeException("Store failure");
          }
        };

    SpringAiToolCallingEngine engine =
        new SpringAiToolCallingEngine(
            requireApprovalChatModel(),
            java.util.List.of(fakeToolCallback()),
            chatMemory(),
            alwaysRequireApprovalPolicy(),
            throwingStore,
            new FakeRuntimeBindingResolver(),
            "test-key");

    assertThrows(
        RuntimeException.class,
        () ->
            engine.execute(
                new AgentDefinition("test", "test"),
                new AgentRequest("test"),
                AgentExecutionContext.stateless()));
  }

  @Test
  void durableMode_resolverNotCalledDuringInitialSuspension() {
    FakeRuntimeBindingResolver resolver = new FakeRuntimeBindingResolver();

    SpringAiToolCallingEngine engine =
        new SpringAiToolCallingEngine(
            requireApprovalChatModel(),
            java.util.List.of(fakeToolCallback()),
            chatMemory(),
            alwaysRequireApprovalPolicy(),
            new FakeCheckpointStore(),
            resolver,
            "test-key");

    engine.execute(
        new AgentDefinition("test", "test"),
        new AgentRequest("test"),
        AgentExecutionContext.stateless());

    assertEquals(0, resolver.resolveCallCount.get());
  }

  @Test
  void ephemeralMode_requireApproval_usesM4Behavior() {
    SpringAiToolCallingEngine engine =
        new SpringAiToolCallingEngine(
            requireApprovalChatModel(),
            java.util.List.of(fakeToolCallback()),
            chatMemory(),
            alwaysRequireApprovalPolicy());

    AgentResult result =
        engine.execute(
            new AgentDefinition("test", "test"),
            new AgentRequest("test"),
            AgentExecutionContext.stateless());

    assertTrue(result.isSuspended());
    assertEquals(ProcessStatus.WAITING, result.process().status());
  }

  private ChatMemory chatMemory() {
    return MessageWindowChatMemory.builder().build();
  }

  private ChatModel fakeChatModel() {
    return prompt -> {
      throw new UnsupportedOperationException("Not used");
    };
  }

  private ChatModel requireApprovalChatModel() {
    return new ChatModel() {
      @Override
      public ChatResponse call(org.springframework.ai.chat.prompt.Prompt prompt) {
        // Return response with tool call to trigger REQUIRE_APPROVAL
        return new ChatResponse(
            java.util.List.of(
                new Generation(
                    org.springframework.ai.chat.messages.AssistantMessage.builder()
                        .content("Need approval")
                        .toolCalls(
                            java.util.List.of(
                                new org.springframework.ai.chat.messages.AssistantMessage.ToolCall(
                                    "tc-1", "function", "testTool", "{}")))
                        .build())));
      }

      @Override
      public org.springframework.ai.chat.prompt.ChatOptions getOptions() {
        // Must return ToolCallingChatOptions for Spring AI tool injection
        return org.springframework.ai.model.tool.ToolCallingChatOptions.builder().build();
      }
    };
  }

  private ToolGovernancePolicy alwaysRequireApprovalPolicy() {
    return (toolName, arguments, context) -> GovernanceDecision.REQUIRE_APPROVAL;
  }

  private ToolCallback fakeToolCallback() {
    return new ToolCallback() {
      @Override
      public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
            .name("testTool")
            .description("test tool")
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
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean deleteIfVersion(String processId, long expectedVersion) {
      throw new UnsupportedOperationException();
    }
  }

  private static class FakeRuntimeBindingResolver implements RuntimeBindingResolver {
    final AtomicInteger resolveCallCount = new AtomicInteger(0);

    @Override
    public RuntimeBinding resolve(String processId, String runtimeBindingKey, String sessionId) {
      resolveCallCount.incrementAndGet();
      throw new UnsupportedOperationException();
    }
  }
}
