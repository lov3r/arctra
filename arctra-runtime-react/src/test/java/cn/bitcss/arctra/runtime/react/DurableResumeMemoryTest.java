package cn.bitcss.arctra.runtime.react;

import static org.junit.jupiter.api.Assertions.*;

import cn.bitcss.arctra.agent.AgentDefinition;
import cn.bitcss.arctra.agent.AgentExecutionContext;
import cn.bitcss.arctra.agent.AgentResult;
import cn.bitcss.arctra.checkpoint.CheckpointStore;
import cn.bitcss.arctra.checkpoint.PendingToolCall;
import cn.bitcss.arctra.checkpoint.SuspensionCheckpoint;
import cn.bitcss.arctra.governance.GovernanceDecision;
import cn.bitcss.arctra.governance.ToolGovernancePolicy;
import cn.bitcss.arctra.process.ContinuationSignal;
import cn.bitcss.arctra.runtime.RuntimeBinding;
import cn.bitcss.arctra.runtime.RuntimeBindingResolver;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * ChatMemory continuity and ordering tests for durable resume.
 *
 * @author lov3r
 * @since M5-T4 Phase 5.2
 */
class DurableResumeMemoryTest {

  /**
   * Invariant #19: ChatMemory H+U+A without duplicate U.
   *
   * <p>Verifies that after successful durable completion, ChatMemory contains original history +
   * User message + final Assistant message, without duplicating the User message.
   */
  @Test
  void completion_chatMemoryContinuity() {
    FakeCheckpointStore store = new FakeCheckpointStore();
    TrackingChatMemory trackingMemory = new TrackingChatMemory();

    // Pre-populate ChatMemory with H + U
    trackingMemory.add("session-1", new UserMessage("History message"));
    trackingMemory.add("session-1", new AssistantMessage("History response"));
    trackingMemory.add("session-1", new UserMessage("User message"));

    ToolCallback toolA =
        new ToolCallback() {
          @Override
          public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder()
                .name("toolA")
                .description("Tool A")
                .inputSchema("{\"type\":\"object\",\"properties\":{}}")
                .build();
          }

          @Override
          public String call(String functionArguments) {
            return "Tool A result";
          }
        };

    SpringAiToolCallingEngine engine =
        new SpringAiToolCallingEngine(
            completionChatModel(),
            List.of(toolA),
            trackingMemory,
            alwaysAllowPolicy(),
            store,
            fakeResolver(),
            "test-key");

    SuspensionCheckpoint checkpoint =
        new SuspensionCheckpoint(
            SuspensionCheckpoint.CURRENT_SCHEMA_VERSION,
            "process-1",
            1L,
            "test-key",
            "session-1",
            List.of(new PendingToolCall("tc-1", "toolA", "{}")),
            List.of());

    store.checkpoints.put("process-1", checkpoint);

    // Resume APPROVED → completion
    AgentResult result =
        engine.resumeProcess("process-1", 1L, new ContinuationSignal.ApprovalSignal(true, "approved"));

    assertFalse(result.isSuspended(), "Should complete");

    // Verify ChatMemory: H + U + final A
    List<Message> finalMemory = trackingMemory.get("session-1");

    // Count message types
    long userCount = finalMemory.stream().filter(m -> m instanceof UserMessage).count();
    long assistantCount =
        finalMemory.stream().filter(m -> m instanceof AssistantMessage).count();

    assertEquals(2, userCount, "Must have 2 User messages (no duplicate)");
    assertEquals(2, assistantCount, "Must have 2 Assistant messages (history + final)");

    // Verify final Assistant message
    Message lastMessage = finalMemory.get(finalMemory.size() - 1);
    assertTrue(lastMessage instanceof AssistantMessage, "Last message must be Assistant");
    AssistantMessage finalAssistant = (AssistantMessage) lastMessage;
    assertEquals("Final answer", finalAssistant.getText());
  }

  /**
   * Invariant #13: CHECK B conflict prevents ChatMemory write.
   *
   * <p>Verifies that when deleteIfVersion fails (completion conflict), final Assistant message is
   * NOT written to ChatMemory.
   */
  @Test
  void completionCheckBConflict_preventsChatMemoryWrite() {
    ConflictingCheckpointStore store = new ConflictingCheckpointStore();
    TrackingChatMemory trackingMemory = new TrackingChatMemory();

    // Pre-populate with H + U
    trackingMemory.add("session-1", new UserMessage("User message"));

    ToolCallback toolA =
        new ToolCallback() {
          @Override
          public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder()
                .name("toolA")
                .description("Tool A")
                .inputSchema("{\"type\":\"object\",\"properties\":{}}")
                .build();
          }

          @Override
          public String call(String functionArguments) {
            return "Tool A result";
          }
        };

    SpringAiToolCallingEngine engine =
        new SpringAiToolCallingEngine(
            completionChatModel(),
            List.of(toolA),
            trackingMemory,
            alwaysAllowPolicy(),
            store,
            fakeResolver(),
            "test-key");

    SuspensionCheckpoint checkpoint =
        new SuspensionCheckpoint(
            SuspensionCheckpoint.CURRENT_SCHEMA_VERSION,
            "process-1",
            1L,
            "test-key",
            "session-1",
            List.of(new PendingToolCall("tc-1", "toolA", "{}")),
            List.of());

    store.checkpoints.put("process-1", checkpoint);

    // Resume APPROVED → deleteIfVersion returns false → conflict exception
    assertThrows(
        cn.bitcss.arctra.checkpoint.CheckpointTransitionConflictException.class,
        () -> engine.resumeProcess("process-1", 1L, new ContinuationSignal.ApprovalSignal(true, "approved")));

    // Verify: NO final Assistant written
    List<Message> finalMemory = trackingMemory.get("session-1");
    assertEquals(1, finalMemory.size(), "Must have only 1 message (original User)");
    assertTrue(finalMemory.get(0) instanceof UserMessage, "Must be original User message");

    // Verify: No Assistant message added
    long assistantCount =
        finalMemory.stream().filter(m -> m instanceof AssistantMessage).count();
    assertEquals(0, assistantCount, "NO Assistant message must be written after CHECK B conflict");
  }

  /**
   * Invariant #17: sessionId preserved across re-suspension.
   */
  @Test
  void reSuspension_preservesSessionId() {
    FakeCheckpointStore store = new FakeCheckpointStore();

    ToolCallback toolA =
        new ToolCallback() {
          @Override
          public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder()
                .name("toolA")
                .description("Tool A")
                .inputSchema("{\"type\":\"object\",\"properties\":{}}")
                .build();
          }

          @Override
          public String call(String functionArguments) {
            return "Tool A result";
          }
        };

    ToolCallback toolB =
        new ToolCallback() {
          @Override
          public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder()
                .name("toolB")
                .description("Tool B")
                .inputSchema("{\"type\":\"object\",\"properties\":{}}")
                .build();
          }

          @Override
          public String call(String functionArguments) {
            return "Tool B result";
          }
        };

    SpringAiToolCallingEngine engine =
        new SpringAiToolCallingEngine(
            emitsToolBChatModel(),
            List.of(toolA, toolB),
            chatMemory(),
            requireApprovalForToolBPolicy(),
            store,
            fakeResolver(),
            "test-key");

    // Checkpoint v1 with sessionId = "session-123"
    SuspensionCheckpoint checkpoint =
        new SuspensionCheckpoint(
            SuspensionCheckpoint.CURRENT_SCHEMA_VERSION,
            "process-1",
            1L,
            "test-key",
            "session-123",
            List.of(new PendingToolCall("tc-1", "toolA", "{}")),
            List.of());

    store.checkpoints.put("process-1", checkpoint);

    // Resume APPROVED → re-suspension with Tool B
    AgentResult result =
        engine.resumeProcess("process-1", 1L, new ContinuationSignal.ApprovalSignal(true, "approved"));

    assertTrue(result.isSuspended(), "Should re-suspend for Tool B");

    // Verify checkpoint v2
    SuspensionCheckpoint nextCheckpoint = store.checkpoints.get("process-1");
    assertNotNull(nextCheckpoint);
    assertEquals(2L, nextCheckpoint.checkpointVersion(), "Version must be v2");
    assertEquals("process-1", nextCheckpoint.processId(), "processId must be preserved");
    assertEquals("test-key", nextCheckpoint.runtimeBindingKey(), "bindingKey must be preserved");
    assertEquals("session-123", nextCheckpoint.sessionId(), "sessionId must be preserved");
  }

  private ChatMemory chatMemory() {
    return MessageWindowChatMemory.builder().build();
  }

  private ChatModel completionChatModel() {
    return new ChatModel() {
      @Override
      public ChatResponse call(org.springframework.ai.chat.prompt.Prompt prompt) {
        return new ChatResponse(
            List.of(new Generation(new AssistantMessage("Final answer"))));
      }

      @Override
      public org.springframework.ai.chat.prompt.ChatOptions getOptions() {
        return org.springframework.ai.model.tool.ToolCallingChatOptions.builder().build();
      }
    };
  }

  private ChatModel emitsToolBChatModel() {
    return new ChatModel() {
      @Override
      public ChatResponse call(org.springframework.ai.chat.prompt.Prompt prompt) {
        return new ChatResponse(
            List.of(
                new Generation(
                    AssistantMessage.builder()
                        .content("Need Tool B")
                        .toolCalls(
                            List.of(
                                new AssistantMessage.ToolCall(
                                    "tc-2", "function", "toolB", "{}")))
                        .build())));
      }

      @Override
      public org.springframework.ai.chat.prompt.ChatOptions getOptions() {
        return org.springframework.ai.model.tool.ToolCallingChatOptions.builder().build();
      }
    };
  }

  private ToolGovernancePolicy alwaysAllowPolicy() {
    return (toolName, arguments, context) -> GovernanceDecision.ALLOW;
  }

  private ToolGovernancePolicy requireApprovalForToolBPolicy() {
    return (toolName, arguments, context) -> {
      if ("toolB".equals(toolName)) {
        return GovernanceDecision.REQUIRE_APPROVAL;
      }
      return GovernanceDecision.ALLOW;
    };
  }

  private RuntimeBindingResolver fakeResolver() {
    return (processId, runtimeBindingKey, sessionId) ->
        new RuntimeBinding(
            new AgentDefinition("test", "test"),
            AgentExecutionContext.withSession(sessionId));
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

  /**
   * Store that always returns false for deleteIfVersion (simulates completion conflict).
   */
  private static class ConflictingCheckpointStore implements CheckpointStore {
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
      return false;
    }

    @Override
    public boolean deleteIfVersion(String processId, long expectedVersion) {
      return false; // Always conflict
    }
  }

  /**
   * ChatMemory that tracks all add() operations for verification.
   */
  private static class TrackingChatMemory implements ChatMemory {
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
