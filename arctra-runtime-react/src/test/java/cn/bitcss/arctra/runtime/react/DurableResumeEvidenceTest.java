package cn.bitcss.arctra.runtime.react;

import static org.junit.jupiter.api.Assertions.*;

import cn.bitcss.arctra.agent.AgentDefinition;
import cn.bitcss.arctra.agent.AgentExecutionContext;
import cn.bitcss.arctra.agent.AgentResult;
import cn.bitcss.arctra.checkpoint.CheckpointStore;
import cn.bitcss.arctra.checkpoint.PendingToolCall;
import cn.bitcss.arctra.checkpoint.SuspensionCheckpoint;
import cn.bitcss.arctra.evidence.Evidence;
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
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * Evidence ownership and merge correctness tests for durable resume.
 *
 * @author lov3r
 * @since M5-T4 Phase 5.2
 */
class DurableResumeEvidenceTest {

  /**
   * Invariant #9: Historical + new Evidence no duplication.
   *
   * <p>Regression test for ProtocolReconstructor bug where checkpointEvidences were copied into
   * newEvidences sink, causing 2× historical evidence count after merge.
   */
  @Test
  void approvedResume_evidenceNoDuplication() {
    FakeCheckpointStore store = new FakeCheckpointStore();
    AtomicInteger toolExecutionCount = new AtomicInteger(0);

    // Tool B that produces Evidence B
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
            toolExecutionCount.incrementAndGet();
            return "Evidence B content";
          }
        };

    SpringAiToolCallingEngine engine =
        new SpringAiToolCallingEngine(
            completionChatModel(),
            List.of(toolB),
            chatMemory(),
            alwaysAllowPolicy(),
            store,
            fakeResolver(),
            "test-key");

    // Create checkpoint with historical Evidence A
    Evidence evidenceA = new Evidence("test:source", "Evidence A content");
    SuspensionCheckpoint checkpoint =
        new SuspensionCheckpoint(
            SuspensionCheckpoint.CURRENT_SCHEMA_VERSION,
            "process-1",
            1L,
            "test-key",
            "session-1",
            List.of(new PendingToolCall("tc-1", "toolB", "{}")),
            List.of(evidenceA));

    store.checkpoints.put("process-1", checkpoint);

    // Resume APPROVED
    AgentResult result =
        engine.resumeProcess("process-1", 1L, new ContinuationSignal.ApprovalSignal(true, "approved"));

    // Verify evidences
    List<Evidence> finalEvidences = result.evidences();

    // MUST be exactly 2: [A, B]
    assertEquals(2, finalEvidences.size(), "Expected exactly 2 evidences: historical A + new B");

    // Count occurrences
    long countA =
        finalEvidences.stream()
            .filter(e -> e.content().equals("Evidence A content"))
            .count();
    long countB =
        finalEvidences.stream()
            .filter(e -> e.content().equals("Evidence B content"))
            .count();

    assertEquals(1, countA, "Evidence A must appear exactly once (no duplication)");
    assertEquals(1, countB, "Evidence B must appear exactly once");

    // Tool B must have executed
    assertEquals(1, toolExecutionCount.get(), "Tool B must execute exactly once");
  }

  /**
   * Invariant #8: Rejected batch creates no new Evidence.
   */
  @Test
  void rejectedResume_noNewEvidence() {
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

    // Checkpoint with historical Evidence A
    Evidence evidenceA = new Evidence("test:source", "Evidence A");
    SuspensionCheckpoint checkpoint =
        new SuspensionCheckpoint(
            SuspensionCheckpoint.CURRENT_SCHEMA_VERSION,
            "process-1",
            1L,
            "test-key",
            "session-1",
            List.of(new PendingToolCall("tc-1", "toolA", "{}")),
            List.of(evidenceA));

    store.checkpoints.put("process-1", checkpoint);

    // Resume REJECTED
    AgentResult result =
        engine.resumeProcess("process-1", 1L, new ContinuationSignal.ApprovalSignal(false, "rejected"));

    // Verify: ONLY historical evidence, NO new evidence
    List<Evidence> finalEvidences = result.evidences();
    assertEquals(
        1, finalEvidences.size(), "Rejected resume must have only historical evidence (no new)");
    assertEquals(
        "Evidence A", finalEvidences.get(0).content(), "Must be original historical evidence");

    // Tool A must NOT have executed
    assertEquals(0, toolExecutionCount.get(), "Tool A must NOT execute when rejected");
  }

  private ChatMemory chatMemory() {
    return MessageWindowChatMemory.builder().build();
  }

  private ChatModel completionChatModel() {
    return new ChatModel() {
      @Override
      public ChatResponse call(org.springframework.ai.chat.prompt.Prompt prompt) {
        // Return final answer (no tool calls)
        return new ChatResponse(
            List.of(new Generation(new AssistantMessage("Final answer"))));
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
