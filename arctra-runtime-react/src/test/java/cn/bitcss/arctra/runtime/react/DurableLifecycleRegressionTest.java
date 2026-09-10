package cn.bitcss.arctra.runtime.react;

import static org.assertj.core.api.Assertions.*;

import cn.bitcss.arctra.agent.AgentDefinition;
import cn.bitcss.arctra.agent.AgentExecutionContext;
import cn.bitcss.arctra.agent.AgentRequest;
import cn.bitcss.arctra.agent.AgentResult;
import cn.bitcss.arctra.checkpoint.CheckpointStore;
import cn.bitcss.arctra.checkpoint.SuspensionCheckpoint;
import cn.bitcss.arctra.evidence.Evidence;
import cn.bitcss.arctra.governance.GovernanceDecision;
import cn.bitcss.arctra.governance.ToolGovernancePolicy;
import cn.bitcss.arctra.process.ContinuationSignal;
import cn.bitcss.arctra.runtime.DefaultAgentRuntime;
import cn.bitcss.arctra.runtime.RuntimeBinding;
import cn.bitcss.arctra.runtime.RuntimeBindingResolver;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
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
 * M5-T4 Phase 9: Memory/Evidence/Governance System Lifecycle Regression.
 *
 * <p>Comprehensive validation of three critical systems across full durable lifecycle:
 *
 * <pre>
 * Initial → suspend (Tool A pending)
 * Resume A → re-suspend (Tool B pending)
 * Resume B → complete
 * </pre>
 *
 * <p>Validates:
 *
 * <ul>
 *   <li>ChatMemory: no duplicate User, final Assistant only at completion
 *   <li>Evidence: monotonic accumulation [A] → [A,B], no duplication
 *   <li>Governance: stored batches not re-governed, new ToolCalls governed
 * </ul>
 *
 * @author lov3r
 * @since M5-T4 Phase 9
 */
@DisplayName("M5-T4 Phase 9: Lifecycle Regression")
class DurableLifecycleRegressionTest {

  private static final String SESSION_ID = "session-123";
  private static final String BINDING_KEY = "test-agent";

  /**
   * Complete lifecycle regression: initial → re-suspend → complete.
   *
   * <p>Validates all three systems (Memory/Evidence/Governance) together.
   */
  @Test
  @DisplayName("Full lifecycle: Memory + Evidence + Governance")
  void fullLifecycle_memoryEvidenceGovernance() {
    SharedCheckpointStore store = new SharedCheckpointStore();
    SharedChatMemory memory = new SharedChatMemory();

    // Pre-populate history
    memory.add(SESSION_ID, new UserMessage("History message"));
    memory.add(SESSION_ID, new AssistantMessage("History response"));

    // Governance tracking
    Map<String, AtomicInteger> governanceCounts = new ConcurrentHashMap<>();
    governanceCounts.put("toolA", new AtomicInteger(0));
    governanceCounts.put("toolB", new AtomicInteger(0));

    ToolGovernancePolicy policy =
        (toolName, arguments, context) -> {
          governanceCounts.computeIfAbsent(toolName, k -> new AtomicInteger(0)).incrementAndGet();
          return GovernanceDecision.REQUIRE_APPROVAL;
        };

    // Tool execution tracking
    AtomicInteger toolAExecutionCount = new AtomicInteger(0);
    AtomicInteger toolBExecutionCount = new AtomicInteger(0);

    ToolCallback toolA = createTool("toolA", toolAExecutionCount);
    ToolCallback toolB = createTool("toolB", toolBExecutionCount);

    // Model: initial → toolA, after A → toolB, after B → complete
    AtomicInteger modelCallCount = new AtomicInteger(0);
    ChatModel model =
        new ChatModel() {
          @Override
          public ChatResponse call(org.springframework.ai.chat.prompt.Prompt prompt) {
            int call = modelCallCount.incrementAndGet();
            if (call == 1) {
              // Initial: emit toolA
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
            } else if (call == 2) {
              // After toolA: emit toolB
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
              return new ChatResponse(
                  List.of(new Generation(new AssistantMessage("Final answer"))));
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
            List.of(toolA, toolB),
            memory,
            policy,
            store,
            createResolver(),
            BINDING_KEY);

    DefaultAgentRuntime runtime = new DefaultAgentRuntime(engine);

    // ========== PHASE 1: INITIAL EXECUTION → SUSPEND WITH TOOL A ==========

    AgentResult result1 =
        runtime.execute(
            new AgentDefinition("test", "test"),
            new AgentRequest("User request"),
            AgentExecutionContext.withSession(SESSION_ID));

    assertThat(result1.isSuspended()).isTrue();
    String processId = result1.process().id();

    // Assert checkpoint v1
    SuspensionCheckpoint cp1 = store.load(processId).orElseThrow();
    assertThat(cp1.checkpointVersion()).isEqualTo(1L);
    assertThat(cp1.pendingBatch()).hasSize(1);
    assertThat(cp1.pendingBatch().get(0).toolName()).isEqualTo("toolA");
    assertThat(cp1.accumulatedEvidences()).isEmpty(); // No evidence yet

    // Assert ChatMemory: H + U, no final Assistant
    List<Message> memoryAfterSuspend = memory.get(SESSION_ID);
    long userCountAfterSuspend =
        memoryAfterSuspend.stream().filter(m -> m instanceof UserMessage).count();
    long assistantCountAfterSuspend =
        memoryAfterSuspend.stream().filter(m -> m instanceof AssistantMessage).count();

    assertThat(userCountAfterSuspend)
        .as("After initial suspend: should have history User + request User")
        .isGreaterThanOrEqualTo(2);

    // No final Assistant yet (may have history Assistant)
    String lastMessageText = getLastAssistantText(memoryAfterSuspend);
    assertThat(lastMessageText)
        .as("No final answer yet")
        .isNotEqualTo("Final answer");

    // Assert Governance: toolA governed once
    assertThat(governanceCounts.get("toolA").get())
        .as("toolA governed during initial execution")
        .isEqualTo(1);
    assertThat(governanceCounts.get("toolB").get())
        .as("toolB not yet seen")
        .isZero();

    // ========== PHASE 2: RESUME V1 APPROVED → EXECUTE TOOL A → RE-SUSPEND WITH TOOL B ==========

    AgentResult result2 =
        runtime.resumeProcess(
            processId, 1L, new ContinuationSignal.ApprovalSignal(true, "approved"));

    assertThat(result2.isSuspended()).isTrue();

    // Assert checkpoint v2
    SuspensionCheckpoint cp2 = store.load(processId).orElseThrow();
    assertThat(cp2.checkpointVersion()).isEqualTo(2L);
    assertThat(cp2.pendingBatch()).hasSize(1);
    assertThat(cp2.pendingBatch().get(0).toolName()).isEqualTo("toolB");

    // Assert Evidence: [toolA]
    List<Evidence> evidenceAfterA = cp2.accumulatedEvidences();
    assertThat(evidenceAfterA).hasSize(1);
    assertThat(evidenceAfterA.get(0).source()).isEqualTo("tool:toolA");

    // Assert toolA executed exactly once
    assertThat(toolAExecutionCount.get()).isEqualTo(1);

    // Assert ChatMemory: still H + U, no final Assistant
    List<Message> memoryAfterReSuspend = memory.get(SESSION_ID);
    long userCountAfterReSuspend =
        memoryAfterReSuspend.stream().filter(m -> m instanceof UserMessage).count();

    assertThat(userCountAfterReSuspend)
        .as("User message count should not increase during re-suspension")
        .isEqualTo(userCountAfterSuspend);

    String lastMessageAfterReSuspend = getLastAssistantText(memoryAfterReSuspend);
    assertThat(lastMessageAfterReSuspend)
        .as("Still no final answer after re-suspension")
        .isNotEqualTo("Final answer");

    // Assert Governance: toolA NOT re-governed, toolB governed once
    assertThat(governanceCounts.get("toolA").get())
        .as("toolA NOT re-governed on resume")
        .isEqualTo(1);
    assertThat(governanceCounts.get("toolB").get())
        .as("toolB governed (new ToolCall)")
        .isEqualTo(1);

    // ========== PHASE 3: RESUME V2 APPROVED → EXECUTE TOOL B → COMPLETE ==========

    AgentResult result3 =
        runtime.resumeProcess(
            processId, 2L, new ContinuationSignal.ApprovalSignal(true, "approved"));

    assertThat(result3.isCompleted()).isTrue();

    // Assert checkpoint deleted
    assertThat(store.load(processId)).isEmpty();

    // Assert Evidence: [toolA, toolB]
    List<Evidence> finalEvidence = result3.evidences();
    assertThat(finalEvidence).hasSize(2);
    assertThat(finalEvidence.get(0).source()).isEqualTo("tool:toolA");
    assertThat(finalEvidence.get(1).source()).isEqualTo("tool:toolB");

    // Assert no duplication
    long toolACount = finalEvidence.stream().filter(e -> e.source().equals("tool:toolA")).count();
    long toolBCount = finalEvidence.stream().filter(e -> e.source().equals("tool:toolB")).count();
    assertThat(toolACount).isEqualTo(1);
    assertThat(toolBCount).isEqualTo(1);

    // Assert tools executed exactly once each
    assertThat(toolAExecutionCount.get()).isEqualTo(1);
    assertThat(toolBExecutionCount.get()).isEqualTo(1);

    // Assert ChatMemory: H + U + final A
    List<Message> finalMemory = memory.get(SESSION_ID);
    long finalUserCount = finalMemory.stream().filter(m -> m instanceof UserMessage).count();
    long finalAssistantCount =
        finalMemory.stream().filter(m -> m instanceof AssistantMessage).count();

    assertThat(finalUserCount)
        .as("User message count unchanged at completion")
        .isEqualTo(userCountAfterSuspend);

    // Final Assistant should be present
    String lastFinalMessage = getLastAssistantText(finalMemory);
    assertThat(lastFinalMessage)
        .as("Final answer present after completion")
        .isEqualTo("Final answer");

    // Count "Final answer" occurrences (should be exactly 1)
    long finalAnswerCount =
        finalMemory.stream()
            .filter(m -> m instanceof AssistantMessage)
            .filter(m -> "Final answer".equals(((AssistantMessage) m).getText()))
            .count();
    assertThat(finalAnswerCount).isEqualTo(1);

    // Assert Governance: toolB NOT re-governed
    assertThat(governanceCounts.get("toolA").get())
        .as("toolA total governance count")
        .isEqualTo(1);
    assertThat(governanceCounts.get("toolB").get())
        .as("toolB total governance count (NOT re-governed)")
        .isEqualTo(1);
  }

  /**
   * Rejection scenario: Tool A rejected → no execution, no evidence.
   */
  @Test
  @DisplayName("Rejection: no execution, no evidence")
  void rejection_noExecutionNoEvidence() {
    SharedCheckpointStore store = new SharedCheckpointStore();
    SharedChatMemory memory = new SharedChatMemory();

    AtomicInteger governanceCount = new AtomicInteger(0);
    AtomicInteger toolAExecutionCount = new AtomicInteger(0);

    ToolGovernancePolicy policy =
        (toolName, arguments, context) -> {
          governanceCount.incrementAndGet();
          return GovernanceDecision.REQUIRE_APPROVAL;
        };

    ToolCallback toolA = createTool("toolA", toolAExecutionCount);

    // Model: emit toolA, then complete
    AtomicInteger modelCallCount = new AtomicInteger(0);
    ChatModel model =
        new ChatModel() {
          @Override
          public ChatResponse call(org.springframework.ai.chat.prompt.Prompt prompt) {
            int call = modelCallCount.incrementAndGet();
            if (call == 1) {
              // Initial: emit toolA
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
            } else {
              // After rejection: complete
              return new ChatResponse(
                  List.of(new Generation(new AssistantMessage("Completed after rejection"))));
            }
          }

          @Override
          public org.springframework.ai.chat.prompt.ChatOptions getOptions() {
            return org.springframework.ai.model.tool.ToolCallingChatOptions.builder().build();
          }
        };

    SpringAiToolCallingEngine engine =
        new SpringAiToolCallingEngine(
            model, List.of(toolA), memory, policy, store, createResolver(), BINDING_KEY);

    DefaultAgentRuntime runtime = new DefaultAgentRuntime(engine);

    // Initial suspend
    AgentResult result1 =
        runtime.execute(
            new AgentDefinition("test", "test"),
            new AgentRequest("test"),
            AgentExecutionContext.withSession(SESSION_ID));

    assertThat(result1.isSuspended()).isTrue();
    String processId = result1.process().id();

    // Governance count after initial
    assertThat(governanceCount.get()).isEqualTo(1);

    // Resume REJECTED
    AgentResult result2 =
        runtime.resumeProcess(
            processId, 1L, new ContinuationSignal.ApprovalSignal(false, "rejected"));

    assertThat(result2.isCompleted()).isTrue();

    // Assert toolA NOT executed
    assertThat(toolAExecutionCount.get()).isZero();

    // Assert no evidence for toolA
    List<Evidence> finalEvidence = result2.evidences();
    long toolAEvidenceCount =
        finalEvidence.stream().filter(e -> e.source().equals("tool:toolA")).count();
    assertThat(toolAEvidenceCount).isZero();

    // Assert final Assistant written once
    List<Message> finalMemory = memory.get(SESSION_ID);
    long completedCount =
        finalMemory.stream()
            .filter(m -> m instanceof AssistantMessage)
            .filter(m -> "Completed after rejection".equals(((AssistantMessage) m).getText()))
            .count();
    assertThat(completedCount).isEqualTo(1);

    // Assert checkpoint deleted
    assertThat(store.load(processId)).isEmpty();
  }

  /**
   * Three-episode evidence accumulation: A → B → C.
   */
  @Test
  @DisplayName("Three episodes: Evidence [A, B, C]")
  void threeEpisodes_evidenceABC() {
    SharedCheckpointStore store = new SharedCheckpointStore();
    SharedChatMemory memory = new SharedChatMemory();

    AtomicInteger toolACount = new AtomicInteger(0);
    AtomicInteger toolBCount = new AtomicInteger(0);
    AtomicInteger toolCCount = new AtomicInteger(0);

    ToolCallback toolA = createTool("toolA", toolACount);
    ToolCallback toolB = createTool("toolB", toolBCount);
    ToolCallback toolC = createTool("toolC", toolCCount);

    // Model: A → B → C → complete
    AtomicInteger modelCallCount = new AtomicInteger(0);
    ChatModel model =
        new ChatModel() {
          @Override
          public ChatResponse call(org.springframework.ai.chat.prompt.Prompt prompt) {
            int call = modelCallCount.incrementAndGet();
            if (call == 1) {
              return createToolCallResponse("toolA", "tc-1");
            } else if (call == 2) {
              return createToolCallResponse("toolB", "tc-2");
            } else if (call == 3) {
              return createToolCallResponse("toolC", "tc-3");
            } else {
              return new ChatResponse(
                  List.of(new Generation(new AssistantMessage("Final"))));
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
            List.of(toolA, toolB, toolC),
            memory,
            (t, a, c) -> GovernanceDecision.REQUIRE_APPROVAL,
            store,
            createResolver(),
            BINDING_KEY);

    DefaultAgentRuntime runtime = new DefaultAgentRuntime(engine);

    // Initial → A
    AgentResult r1 =
        runtime.execute(
            new AgentDefinition("test", "test"),
            new AgentRequest("test"),
            AgentExecutionContext.withSession(SESSION_ID));
    assertThat(r1.isSuspended()).isTrue();
    String processId = r1.process().id();

    // Resume v1 → B
    AgentResult r2 =
        runtime.resumeProcess(
            processId, 1L, new ContinuationSignal.ApprovalSignal(true, "approved"));
    assertThat(r2.isSuspended()).isTrue();

    // Resume v2 → C
    AgentResult r3 =
        runtime.resumeProcess(
            processId, 2L, new ContinuationSignal.ApprovalSignal(true, "approved"));
    assertThat(r3.isSuspended()).isTrue();

    // Resume v3 → complete
    AgentResult r4 =
        runtime.resumeProcess(
            processId, 3L, new ContinuationSignal.ApprovalSignal(true, "approved"));
    assertThat(r4.isCompleted()).isTrue();

    // Assert evidence [A, B, C]
    List<Evidence> finalEvidence = r4.evidences();
    assertThat(finalEvidence).hasSize(3);
    assertThat(finalEvidence.get(0).source()).isEqualTo("tool:toolA");
    assertThat(finalEvidence.get(1).source()).isEqualTo("tool:toolB");
    assertThat(finalEvidence.get(2).source()).isEqualTo("tool:toolC");

    // Assert no duplication
    long aCount = finalEvidence.stream().filter(e -> e.source().equals("tool:toolA")).count();
    long bCount = finalEvidence.stream().filter(e -> e.source().equals("tool:toolB")).count();
    long cCount = finalEvidence.stream().filter(e -> e.source().equals("tool:toolC")).count();
    assertThat(aCount).isEqualTo(1);
    assertThat(bCount).isEqualTo(1);
    assertThat(cCount).isEqualTo(1);

    // Assert each tool executed once
    assertThat(toolACount.get()).isEqualTo(1);
    assertThat(toolBCount.get()).isEqualTo(1);
    assertThat(toolCCount.get()).isEqualTo(1);
  }

  // ========== HELPER METHODS ==========

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

  private RuntimeBindingResolver createResolver() {
    return (processId, runtimeBindingKey, sessionId) ->
        new RuntimeBinding(
            new AgentDefinition("test", "test"),
            AgentExecutionContext.withSession(sessionId));
  }

  private String getLastAssistantText(List<Message> messages) {
    for (int i = messages.size() - 1; i >= 0; i--) {
      if (messages.get(i) instanceof AssistantMessage) {
        return ((AssistantMessage) messages.get(i)).getText();
      }
    }
    return null;
  }

  private ChatResponse createToolCallResponse(String toolName, String callId) {
    return new ChatResponse(
        List.of(
            new Generation(
                AssistantMessage.builder()
                    .content("Need " + toolName)
                    .toolCalls(
                        List.of(
                            new AssistantMessage.ToolCall(
                                callId, "function", toolName, "{}")))
                    .build())));
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
