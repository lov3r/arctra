package cn.bitcss.arctra.runtime.react;

import static org.assertj.core.api.Assertions.assertThat;

import cn.bitcss.arctra.checkpoint.PendingToolCall;
import cn.bitcss.arctra.evidence.Evidence;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * Tests for ProtocolReconstructor - production reconstruction seam.
 *
 * <p>Verifies that durable checkpoint DTOs can be reconstructed into valid Spring AI tool protocol
 * using NEW runtime tool instances.
 *
 * @author lov3r
 * @since M5-T4.1
 */
class ProtocolReconstructorTest {

  @Test
  void executeApprovedBatch_reconstructsProtocolWithNewToolInstances() {
    // GIVEN: Checkpoint DTO with pending tool call
    PendingToolCall dto = new PendingToolCall("call_001", "investigate", "{}");
    List<PendingToolCall> pendingBatch = List.of(dto);

    // GIVEN: NEW tool callback instance (simulates runtime restart)
    AtomicInteger executionCount = new AtomicInteger(0);
    ToolCallback newToolInstance = createTool("investigate", executionCount);

    // GIVEN: Conversation history and evidences
    List<Message> conversationHistory = List.of(new UserMessage("Investigate incident"));
    List<Evidence> checkpointEvidences = List.of(new Evidence("previous", "data"));
    List<Evidence> newEvidences = Collections.synchronizedList(new ArrayList<>());

    // WHEN: Execute approved batch via reconstructor
    ProtocolReconstructor reconstructor = new ProtocolReconstructor(List.of(newToolInstance));
    List<Message> continuationMessages =
        reconstructor.executeApprovedBatch(
            pendingBatch, conversationHistory, checkpointEvidences, newEvidences);

    // THEN: Tool executed with NEW instance
    assertThat(executionCount.get()).isEqualTo(1);

    // THEN: Continuation messages contain ToolResponseMessage
    ToolResponseMessage toolResponse =
        continuationMessages.stream()
            .filter(m -> m instanceof ToolResponseMessage)
            .map(m -> (ToolResponseMessage) m)
            .findFirst()
            .orElseThrow();

    // THEN: Original toolCallId preserved
    String preservedToolCallId = toolResponse.getResponses().get(0).id();
    assertThat(preservedToolCallId).isEqualTo("call_001");

    // THEN: Evidence collected (only NEW execution, NOT checkpoint evidences)
    // Bug fix: ProtocolReconstructor should NOT copy checkpoint evidences into newEvidences
    assertThat(newEvidences).hasSize(1); // Only new tool execution
    assertThat(newEvidences.get(0).source()).isEqualTo("tool:investigate"); // Evidence uses tool: prefix
  }

  @Test
  void executeApprovedBatch_preservesToolName() {
    PendingToolCall dto = new PendingToolCall("call_002", "analyze", "{}");
    AtomicInteger executionCount = new AtomicInteger(0);
    ToolCallback tool = createTool("analyze", executionCount);

    ProtocolReconstructor reconstructor = new ProtocolReconstructor(List.of(tool));
    List<Message> result =
        reconstructor.executeApprovedBatch(
            List.of(dto),
            List.of(new UserMessage("test")),
            List.of(),
            Collections.synchronizedList(new ArrayList<>()));

    assertThat(executionCount.get()).isEqualTo(1);

    ToolResponseMessage toolResponse =
        result.stream()
            .filter(m -> m instanceof ToolResponseMessage)
            .map(m -> (ToolResponseMessage) m)
            .findFirst()
            .orElseThrow();

    assertThat(toolResponse.getResponses().get(0).name()).isEqualTo("analyze");
  }

  @Test
  void executeApprovedBatch_executesBatchWithMultipleTools() {
    PendingToolCall dto1 = new PendingToolCall("call_003", "toolA", "{}");
    PendingToolCall dto2 = new PendingToolCall("call_004", "toolB", "{}");

    AtomicInteger countA = new AtomicInteger(0);
    AtomicInteger countB = new AtomicInteger(0);
    ToolCallback toolA = createTool("toolA", countA);
    ToolCallback toolB = createTool("toolB", countB);

    ProtocolReconstructor reconstructor = new ProtocolReconstructor(List.of(toolA, toolB));
    List<Evidence> newEvidences = Collections.synchronizedList(new ArrayList<>());

    reconstructor.executeApprovedBatch(
        List.of(dto1, dto2), List.of(new UserMessage("test")), List.of(), newEvidences);

    assertThat(countA.get()).isEqualTo(1);
    assertThat(countB.get()).isEqualTo(1);
    assertThat(newEvidences).hasSize(2);
  }

  @Test
  void constructDenialResponses_createsRejectionWithoutExecution() {
    PendingToolCall dto = new PendingToolCall("call_005", "dangerous", "{}");
    AtomicInteger executionCount = new AtomicInteger(0);
    ToolCallback tool = createTool("dangerous", executionCount);

    ProtocolReconstructor reconstructor = new ProtocolReconstructor(List.of(tool));
    List<Message> result =
        reconstructor.constructDenialResponses(
            List.of(dto), List.of(new UserMessage("test")));

    // THEN: Tool NOT executed
    assertThat(executionCount.get()).isZero();

    // THEN: ToolResponseMessage with denial content
    ToolResponseMessage toolResponse =
        result.stream()
            .filter(m -> m instanceof ToolResponseMessage)
            .map(m -> (ToolResponseMessage) m)
            .findFirst()
            .orElseThrow();

    assertThat(toolResponse.getResponses().get(0).id()).isEqualTo("call_005");
    assertThat(toolResponse.getResponses().get(0).responseData())
        .contains("rejected by approval decision");

    // THEN: AssistantMessage with ToolCall included
    AssistantMessage assistantMessage =
        result.stream()
            .filter(m -> m instanceof AssistantMessage)
            .map(m -> (AssistantMessage) m)
            .findFirst()
            .orElseThrow();

    assertThat(assistantMessage.getToolCalls()).hasSize(1);
    assertThat(assistantMessage.getToolCalls().get(0).id()).isEqualTo("call_005");
  }

  @Test
  void constructDenialResponses_handlesBatchRejection() {
    PendingToolCall dto1 = new PendingToolCall("call_006", "toolA", "{}");
    PendingToolCall dto2 = new PendingToolCall("call_007", "toolB", "{}");

    AtomicInteger countA = new AtomicInteger(0);
    AtomicInteger countB = new AtomicInteger(0);
    ToolCallback toolA = createTool("toolA", countA);
    ToolCallback toolB = createTool("toolB", countB);

    ProtocolReconstructor reconstructor = new ProtocolReconstructor(List.of(toolA, toolB));
    List<Message> result =
        reconstructor.constructDenialResponses(
            List.of(dto1, dto2), List.of(new UserMessage("test")));

    // THEN: NO tools executed
    assertThat(countA.get()).isZero();
    assertThat(countB.get()).isZero();

    // THEN: Both denials present
    ToolResponseMessage toolResponse =
        result.stream()
            .filter(m -> m instanceof ToolResponseMessage)
            .map(m -> (ToolResponseMessage) m)
            .findFirst()
            .orElseThrow();

    assertThat(toolResponse.getResponses()).hasSize(2);
    assertThat(toolResponse.getResponses().get(0).id()).isEqualTo("call_006");
    assertThat(toolResponse.getResponses().get(1).id()).isEqualTo("call_007");
  }

  // Helper: Create tool callback
  private ToolCallback createTool(String name, AtomicInteger executionCount) {
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
      public String call(String args) {
        executionCount.incrementAndGet();
        return "result from " + name;
      }
    };
  }
}
