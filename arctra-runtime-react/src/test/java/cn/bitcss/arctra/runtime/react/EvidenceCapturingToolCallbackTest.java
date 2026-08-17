package cn.bitcss.arctra.runtime.react;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.bitcss.arctra.evidence.Evidence;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * Tests for {@link EvidenceCapturingToolCallback}.
 *
 * @author lov3r
 */
class EvidenceCapturingToolCallbackTest {

  @Test
  void should_capture_evidence_on_success() {
    var delegate = new MockToolCallback("testTool", "result");
    List<Evidence> evidences = new ArrayList<>();

    var wrapper = new EvidenceCapturingToolCallback(delegate, evidences);

    var result = wrapper.call("{}");

    assertThat(result).isEqualTo("result");
    assertThat(evidences).hasSize(1);
    assertThat(evidences.get(0).source()).isEqualTo("tool:testTool");
    assertThat(evidences.get(0).content()).isEqualTo("result");
  }

  @Test
  void should_capture_evidence_with_tool_context() {
    var delegate = new MockToolCallback("testTool", "result");
    List<Evidence> evidences = new ArrayList<>();

    var wrapper = new EvidenceCapturingToolCallback(delegate, evidences);

    var result = wrapper.call("{}", new ToolContext(java.util.Map.of()));

    assertThat(result).isEqualTo("result");
    assertThat(evidences).hasSize(1);
    assertThat(evidences.get(0).source()).isEqualTo("tool:testTool");
    assertThat(evidences.get(0).content()).isEqualTo("result");
  }

  @Test
  void should_not_capture_evidence_on_failure() {
    var delegate = new FailingToolCallback();
    List<Evidence> evidences = new ArrayList<>();

    var wrapper = new EvidenceCapturingToolCallback(delegate, evidences);

    assertThatThrownBy(() -> wrapper.call("{}")).isInstanceOf(RuntimeException.class);

    // No evidence captured on failure
    assertThat(evidences).isEmpty();
  }

  @Test
  void should_delegate_tool_definition() {
    var delegate = new MockToolCallback("testTool", "result");
    var wrapper = new EvidenceCapturingToolCallback(delegate, new ArrayList<>());

    assertThat(wrapper.getToolDefinition()).isEqualTo(delegate.getToolDefinition());
  }

  @Test
  void should_delegate_tool_metadata() {
    var delegate = new MockToolCallback("testTool", "result");
    var wrapper = new EvidenceCapturingToolCallback(delegate, new ArrayList<>());

    assertThat(wrapper.getToolMetadata()).isEqualTo(delegate.getToolMetadata());
  }

  @Test
  void should_capture_multiple_invocations() {
    var delegate = new MockToolCallback("testTool", "result");
    List<Evidence> evidences = new ArrayList<>();

    var wrapper = new EvidenceCapturingToolCallback(delegate, evidences);

    wrapper.call("{}");
    wrapper.call("{}");

    // Each invocation creates an evidence
    assertThat(evidences).hasSize(2);
    assertThat(evidences.get(0).source()).isEqualTo("tool:testTool");
    assertThat(evidences.get(1).source()).isEqualTo("tool:testTool");
  }

  /** Mock ToolCallback for testing. */
  static class MockToolCallback implements ToolCallback {

    private final String name;
    private final String result;

    MockToolCallback(String name, String result) {
      this.name = name;
      this.result = result;
    }

    @Override
    public ToolDefinition getToolDefinition() {
      return ToolDefinition.builder()
          .name(name)
          .description("Mock tool")
          .inputSchema("{\"type\":\"object\",\"properties\":{}}")
          .build();
    }

    @Override
    public String call(String functionArguments) {
      return result;
    }

    @Override
    public String call(String functionArguments, ToolContext toolContext) {
      return result;
    }
  }

  /** Failing ToolCallback for testing error handling. */
  static class FailingToolCallback implements ToolCallback {

    @Override
    public ToolDefinition getToolDefinition() {
      return ToolDefinition.builder()
          .name("failing")
          .description("Failing tool")
          .inputSchema("{\"type\":\"object\",\"properties\":{}}")
          .build();
    }

    @Override
    public String call(String functionArguments) {
      throw new RuntimeException("Tool execution failed");
    }
  }
}
