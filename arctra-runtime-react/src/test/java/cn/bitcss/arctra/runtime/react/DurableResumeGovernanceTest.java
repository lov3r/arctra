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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * Governance boundary tests for durable resume.
 *
 * <p>Verifies that stored pending batch is NOT re-governed, but new model-emitted tools ARE
 * governed.
 *
 * @author lov3r
 * @since M5-T4 Phase 5.2
 */
class DurableResumeGovernanceTest {

  /**
   * Invariant #5: Approved stored batch executes exactly once.
   * Invariant #6: Approved stored batch NOT re-governed.
   */
  @Test
  void approvedResume_storedBatchExecutesWithoutReGovernance() {
    FakeCheckpointStore store = new FakeCheckpointStore();
    AtomicInteger toolExecutionCount = new AtomicInteger(0);
    AtomicInteger governanceEvaluationCount = new AtomicInteger(0);

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
            toolExecutionCount.incrementAndGet();
            return "Tool A result";
          }
        };

    // Governance policy that counts evaluations
    ToolGovernancePolicy countingPolicy =
        (toolName, arguments, context) -> {
          if ("toolA".equals(toolName)) {
            governanceEvaluationCount.incrementAndGet();
          }
          return GovernanceDecision.ALLOW;
        };

    SpringAiToolCallingEngine engine =
        new SpringAiToolCallingEngine(
            completionChatModel(),
            List.of(toolA),
            chatMemory(),
            countingPolicy,
            store,
            fakeResolver(),
            "test-key");

    // Checkpoint with pending Tool A (already passed governance with REQUIRE_APPROVAL)
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

    // Resume APPROVED
    AgentResult result =
        engine.resumeProcess("process-1", 1L, new ContinuationSignal.ApprovalSignal(true, "approved"));

    // Verify: Tool A executed exactly once
    assertEquals(1, toolExecutionCount.get(), "Tool A must execute exactly once");

    // Verify: Tool A NOT re-governed during resume (governance count = 0)
    assertEquals(
        0,
        governanceEvaluationCount.get(),
        "Stored Tool A must NOT be re-governed during resume");

    assertFalse(result.isSuspended(), "Should complete normally");
  }

  /**
   * Invariant #7: Rejected stored batch NOT executed.
   */
  @Test
  void rejectedResume_storedBatchNotExecuted() {
    FakeCheckpointStore store = new FakeCheckpointStore();
    AtomicInteger toolExecutionCount = new AtomicInteger(0);

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
            toolExecutionCount.incrementAndGet();
            return "Tool A result";
          }
        };

    SpringAiToolCallingEngine engine =
        new SpringAiToolCallingEngine(
            completionChatModel(),
            List.of(toolA),
            chatMemory(),
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

    // Resume REJECTED
    AgentResult result =
        engine.resumeProcess("process-1", 1L, new ContinuationSignal.ApprovalSignal(false, "rejected"));

    // Verify: Tool A NOT executed
    assertEquals(0, toolExecutionCount.get(), "Tool A must NOT execute when rejected");

    assertFalse(result.isSuspended(), "Should complete with denial");
  }

  /**
   * Invariant #10: New model-emitted ToolCall IS governed normally.
   */
  @Test
  void approvedResume_newToolCallGovernedNormally() {
    FakeCheckpointStore store = new FakeCheckpointStore();
    AtomicInteger toolAGovernanceCount = new AtomicInteger(0);
    AtomicInteger toolBGovernanceCount = new AtomicInteger(0);

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

    // Governance: Tool A not evaluated (stored), Tool B requires approval (new)
    ToolGovernancePolicy countingPolicy =
        (toolName, arguments, context) -> {
          if ("toolA".equals(toolName)) {
            toolAGovernanceCount.incrementAndGet();
            return GovernanceDecision.ALLOW;
          }
          if ("toolB".equals(toolName)) {
            toolBGovernanceCount.incrementAndGet();
            return GovernanceDecision.REQUIRE_APPROVAL;
          }
          return GovernanceDecision.ALLOW;
        };

    SpringAiToolCallingEngine engine =
        new SpringAiToolCallingEngine(
            emitsToolBChatModel(),
            List.of(toolA, toolB),
            chatMemory(),
            countingPolicy,
            store,
            fakeResolver(),
            "test-key");

    // Checkpoint with pending Tool A
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

    // Resume APPROVED → Tool A executes → model emits NEW Tool B
    AgentResult result =
        engine.resumeProcess("process-1", 1L, new ContinuationSignal.ApprovalSignal(true, "approved"));

    // Verify: Tool A NOT governed during resume
    assertEquals(0, toolAGovernanceCount.get(), "Stored Tool A must NOT be governed");

    // Verify: NEW Tool B IS governed
    assertEquals(1, toolBGovernanceCount.get(), "New Tool B must be governed");

    // Verify: Re-suspension with Tool B
    assertTrue(result.isSuspended(), "Should re-suspend for Tool B approval");

    // Verify: Next checkpoint contains Tool B
    SuspensionCheckpoint nextCheckpoint = store.checkpoints.get("process-1");
    assertNotNull(nextCheckpoint, "Next checkpoint must exist");
    assertEquals(2L, nextCheckpoint.checkpointVersion(), "Version must increment to v2");
    assertEquals(1, nextCheckpoint.pendingBatch().size(), "Must have 1 pending tool");
    assertEquals("toolB", nextCheckpoint.pendingBatch().get(0).toolName(), "Must be Tool B");
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
        // After Tool A executes, model emits NEW Tool B
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
}
