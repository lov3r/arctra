package cn.bitcss.arctra.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.bitcss.arctra.evidence.Evidence;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AgentResult}.
 *
 * @author lov3r
 */
class AgentResultTest {

  @Test
  void should_create_result_with_content_only() {
    var result = new AgentResult("Spring AI is a framework for building AI applications");

    assertThat(result.content()).isEqualTo("Spring AI is a framework for building AI applications");
    assertThat(result.evidences()).isEmpty();
  }

  @Test
  void should_create_result_with_evidences() {
    var evidences = List.of(
        new Evidence("queryLogs", "log content"),
        new Evidence("getDeployment", "deployment info")
    );

    var result = new AgentResult("Analysis complete", evidences);

    assertThat(result.content()).isEqualTo("Analysis complete");
    assertThat(result.evidences()).hasSize(2);
    assertThat(result.evidences().get(0).source()).isEqualTo("queryLogs");
    assertThat(result.evidences().get(1).source()).isEqualTo("getDeployment");
  }

  @Test
  void should_allow_empty_content() {
    var result = new AgentResult("");

    assertThat(result.content()).isEmpty();
    assertThat(result.evidences()).isEmpty();
  }

  @Test
  void should_reject_null_content() {
    assertThatThrownBy(() -> new AgentResult(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("content cannot be null");
  }

  @Test
  void should_reject_null_evidences() {
    assertThatThrownBy(() -> new AgentResult("content", null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("evidences cannot be null");
  }

  @Test
  void should_allow_empty_evidences_list() {
    var result = new AgentResult("content", List.of());

    assertThat(result.evidences()).isEmpty();
  }

  @Test
  void should_copy_evidences_list() {
    var evidences = new ArrayList<Evidence>();
    evidences.add(new Evidence("source1", "content1"));

    var result = new AgentResult("answer", evidences);

    // Modify original list should not affect result
    evidences.add(new Evidence("source2", "content2"));

    assertThat(result.evidences()).hasSize(1);
  }

  @Test
  void should_return_immutable_evidences_list() {
    var result = new AgentResult("answer", List.of(new Evidence("source", "content")));

    assertThatThrownBy(() -> result.evidences().add(new Evidence("new", "new")))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void should_maintain_backward_compatibility() {
    // Original usage: new AgentResult(content)
    var result = new AgentResult("test content");

    assertThat(result.content()).isEqualTo("test content");
    assertThat(result.evidences()).isEmpty();
  }
}
