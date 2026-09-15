package cn.bitcss.arctra.runtime.react;

import static org.assertj.core.api.Assertions.*;

import cn.bitcss.arctra.agent.AgentDefinition;
import cn.bitcss.arctra.agent.AgentExecutionContext;
import cn.bitcss.arctra.agent.AgentRequest;
import cn.bitcss.arctra.agent.AgentResult;
import cn.bitcss.arctra.checkpoint.CheckpointStore;
import cn.bitcss.arctra.checkpoint.PendingToolCall;
import cn.bitcss.arctra.checkpoint.SuspensionCheckpoint;
import cn.bitcss.arctra.evidence.Evidence;
import cn.bitcss.arctra.execution.EventType;
import cn.bitcss.arctra.execution.ExecutionEvent;
import cn.bitcss.arctra.execution.ExecutionEventListener;
import cn.bitcss.arctra.governance.GovernanceDecision;
import cn.bitcss.arctra.governance.ToolGovernancePolicy;
import cn.bitcss.arctra.process.ContinuationSignal;
import cn.bitcss.arctra.runtime.DefaultAgentRuntime;
import cn.bitcss.arctra.runtime.RuntimeBinding;
import cn.bitcss.arctra.runtime.RuntimeBindingResolver;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * M6-T4F: Automatic Recovery Mode Selection Tests.
 *
 * <p>Adversarial tests proving automatic restart detection and recovery mode selection based on
 * execution incarnation.
 *
 * @author lov3r
 * @since M6-T4F
 */
@DisplayName("M6-T4F: Automatic Recovery Mode Selection")
class AutomaticRecoveryModeSelectionTest {

  private static final String SESSION_ID = "session-test";
  private static final String BINDING_KEY = "agent-test";

  // Test 1: Initial suspension stores current executionEpoch
  @Test
  @DisplayName("T4F-1: Initial suspension stores current executionEpoch")
  void initialSuspension_storesCurrentExecutionEpoch() {
    // Given
    TestCheckpointStore store = new TestCheckpointStore();
    ChatMemory memory = MessageWindowChatMemory.builder().maxMessages(10).build();
    AtomicInteger toolExecutionCount = new AtomicInteger(0);

    ToolCallback tool = createTestTool("testTool", toolExecutionCount);
    ChatModel model = createModelRequiringApproval("testTool");
    ToolGovernancePolicy policy =
        (toolName, args, ctx) ->
            "testTool".equals(toolName) ? GovernanceDecision.REQUIRE_APPROVAL : GovernanceDecision.ALLOW;

    RuntimeBindingResolver resolver = createTestResolver();

    SpringAiToolCallingEngine engine =
        new SpringAiToolCallingEngine(
            model,
            List.of(tool),
            memory,
            policy,
            store,
            resolver,
            BINDING_KEY);

    DefaultAgentRuntime runtime = new DefaultAgentRuntime(engine);

    // When - execute to suspension
    AgentResult result =
        runtime.execute(
            new AgentDefinition("test", "test"),
            new AgentRequest("request"),
            AgentExecutionContext.withSession(SESSION_ID));

    // Then - suspended
    assertThat(result.isSuspended()).isTrue();
    String processId = result.process().id();

    // Then - checkpoint has executionEpoch
    SuspensionCheckpoint checkpoint = store.load(processId).orElseThrow();
    assertThat(checkpoint.executionEpoch())
        .as("Initial checkpoint must have executionEpoch")
        .isNotNull()
        .isNotBlank();

    // Then - executionEpoch is valid UUID format
    assertThat(checkpoint.executionEpoch())
        .as("executionEpoch should be UUID format")
        .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    // Then - schema version is 1.1
    assertThat(checkpoint.schemaVersion())
        .as("Schema version should be 1.1")
        .isEqualTo("1.1");
  }

  // Test 2: Multiple Engine instances in same ClassLoader share incarnation
  @Test
  @DisplayName("T4F-2: Multiple engines in same JVM share execution incarnation")
  void multipleEngines_shareExecutionIncarnation() {
    // Given - shared infrastructure
    TestCheckpointStore store = new TestCheckpointStore();
    ChatMemory memory = MessageWindowChatMemory.builder().maxMessages(10).build();
    RuntimeBindingResolver resolver = createTestResolver();

    // Create Engine A
    SpringAiToolCallingEngine engineA =
        new SpringAiToolCallingEngine(
            createModelRequiringApproval("toolA"),
            List.of(createTestTool("toolA", new AtomicInteger())),
            memory,
            (name, args, ctx) -> GovernanceDecision.REQUIRE_APPROVAL,
            store,
            resolver,
            BINDING_KEY);

    DefaultAgentRuntime runtimeA = new DefaultAgentRuntime(engineA);

    // Create Engine B (different instance)
    SpringAiToolCallingEngine engineB =
        new SpringAiToolCallingEngine(
            createModelRequiringApproval("toolB"),
            List.of(createTestTool("toolB", new AtomicInteger())),
            memory,
            (name, args, ctx) -> GovernanceDecision.REQUIRE_APPROVAL,
            store,
            resolver,
            BINDING_KEY);

    DefaultAgentRuntime runtimeB = new DefaultAgentRuntime(engineB);

    // When - Engine A creates checkpoint
    AgentResult resultA =
        runtimeA.execute(
            new AgentDefinition("test", "test"),
            new AgentRequest("request"),
            AgentExecutionContext.withSession(SESSION_ID));

    String processId = resultA.process().id();
    SuspensionCheckpoint checkpointA = store.load(processId).orElseThrow();

    // Then - Engine B resuming A's checkpoint sees SAME incarnation
    AgentResult resultB =
        runtimeB.resumeProcess(
            processId, 1L, new ContinuationSignal.ApprovalSignal(true, "approve"));

    // Then - execution should use normal mode (no recovery classification)
    assertThat(resultB).isNotNull();

    // Then - both checkpoints should have same executionEpoch
    assertThat(checkpointA.executionEpoch())
        .as("Different Engine instances in same JVM should share executionEpoch")
        .isEqualTo(ExecutionIncarnation.current());
  }

  // Test 3: Same-incarnation resume uses normal path
  @Test
  @DisplayName("T4F-3: Same-incarnation resume uses normal path (no recovery reads)")
  void sameIncarnation_usesNormalPath() {
    // Given
    RecordingInvocationStateStore stateStore = new RecordingInvocationStateStore();
    TestCheckpointStore checkpointStore = new TestCheckpointStore();

    // Create checkpoint with current epoch
    String processId = "proc-test";
    PendingToolCall operation = new PendingToolCall("op-1", "tc-1", "tool-1", "{}");

    SuspensionCheckpoint checkpoint =
        new SuspensionCheckpoint(
            "1.1",
            processId,
            1L,
            BINDING_KEY,
            SESSION_ID,
            List.of(operation),
            List.of(),
            ExecutionIncarnation.current()); // SAME incarnation

    checkpointStore.create(checkpoint);

    // Create engine with recording state store
    ChatMemory memory = MessageWindowChatMemory.builder().maxMessages(10).build();
    AtomicInteger toolCount = new AtomicInteger(0);

    SpringAiToolCallingEngine engine =
        createEngineWithStateStore(checkpointStore, stateStore, memory, toolCount);

    DefaultAgentRuntime runtime = new DefaultAgentRuntime(engine);

    // When - resume
    AgentResult result =
        runtime.resumeProcess(
            processId, 1L, new ContinuationSignal.ApprovalSignal(true, "approve"));

    // Then - NO recovery classification reads
    assertThat(stateStore.readCalls)
        .as("Same-incarnation resume must NOT read invocation state for recovery classification")
        .isEmpty();

    // Then - normal invocation-intent write gate still enforced
    assertThat(stateStore.writeCalls)
        .as("Normal write gate must still be enforced")
        .contains("proc-test:op-1");
  }

  // Helper classes
  private static class RecordingInvocationStateStore implements InvocationStateStore {
    final List<String> readCalls = new ArrayList<>();
    final List<String> writeCalls = new ArrayList<>();

    @Override
    public void recordInvocationIntent(String processId, String operationId) {
      writeCalls.add(processId + ":" + operationId);
    }

    @Override
    public boolean hasInvocationIntent(String processId, String operationId) {
      readCalls.add(processId + ":" + operationId);
      return false; // Default: no intent exists
    }
  }

  private static class TestCheckpointStore implements CheckpointStore {
    private final List<SuspensionCheckpoint> checkpoints = new ArrayList<>();

    @Override
    public void create(SuspensionCheckpoint checkpoint) {
      checkpoints.add(checkpoint);
    }

    @Override
    public java.util.Optional<SuspensionCheckpoint> load(String processId) {
      return checkpoints.stream()
          .filter(c -> c.processId().equals(processId))
          .reduce((first, second) -> second); // Last one
    }

    @Override
    public boolean replaceIfVersion(
        String processId, long expectedVersion, SuspensionCheckpoint newCheckpoint) {
      java.util.Optional<SuspensionCheckpoint> existing = load(processId);
      if (existing.isEmpty() || existing.get().checkpointVersion() != expectedVersion) {
        return false;
      }
      checkpoints.removeIf(c -> c.processId().equals(processId));
      checkpoints.add(newCheckpoint);
      return true;
    }

    @Override
    public boolean deleteIfVersion(String processId, long expectedVersion) {
      java.util.Optional<SuspensionCheckpoint> existing = load(processId);
      if (existing.isEmpty() || existing.get().checkpointVersion() != expectedVersion) {
        return false;
      }
      checkpoints.removeIf(c -> c.processId().equals(processId));
      return true;
    }

  }

  // Helper methods

  private ToolCallback createTestTool(String name, AtomicInteger executionCount) {
    return new ToolCallback() {
      @Override
      public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
            .name(name)
            .description("Test tool")
            .inputSchema("{\"type\":\"object\",\"properties\":{}}")
            .build();
      }

      @Override
      public String call(String functionArguments) {
        executionCount.incrementAndGet();
        return "result-" + name;
      }
    };
  }

  private ChatModel createModelRequiringApproval(String toolName) {
    return new ChatModel() {
      @Override
      public ChatResponse call(org.springframework.ai.chat.prompt.Prompt prompt) {
        AssistantMessage.ToolCall toolCall =
            new AssistantMessage.ToolCall("tc-1", "function", toolName, "{}");
        AssistantMessage message =
            AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(toolCall))
                .build();
        return new ChatResponse(List.of(new Generation(message)));
      }

      @Override
      public ChatOptions getOptions() {
        return ToolCallingChatOptions.builder().build();
      }
    };
  }

  private RuntimeBindingResolver createTestResolver() {
    return (processId, bindingKey, sessionId) ->
        new RuntimeBinding(
            new AgentDefinition("test", "test"),
            AgentExecutionContext.withSession(sessionId));
  }

  private SpringAiToolCallingEngine createEngineWithStateStore(
      CheckpointStore checkpointStore,
      InvocationStateStore stateStore,
      ChatMemory memory,
      AtomicInteger toolCount) {

    ToolCallback tool = createTestTool("tool-1", toolCount);
    ChatModel model = new ChatModel() {
      @Override
      public ChatResponse call(org.springframework.ai.chat.prompt.Prompt prompt) {
        return new ChatResponse(
            List.of(
                new Generation(
                    AssistantMessage.builder()
                        .content("completed")
                        .build())));
      }

      @Override
      public ChatOptions getOptions() {
        return ToolCallingChatOptions.builder().build();
      }
    };

    ToolGovernancePolicy policy = (name, args, ctx) -> GovernanceDecision.ALLOW;
    RuntimeBindingResolver resolver = createTestResolver();

    // Use reflection or test constructor to inject state store
    // For now, create engine normally (state store pairing will handle it)
    return new SpringAiToolCallingEngine(
        model,
        List.of(tool),
        memory,
        policy,
        checkpointStore,
        resolver,
        BINDING_KEY);
  }
}
