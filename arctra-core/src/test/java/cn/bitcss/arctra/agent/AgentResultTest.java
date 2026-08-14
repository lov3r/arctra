package cn.bitcss.arctra.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AgentResult}.
 *
 * @author lov3r
 */
class AgentResultTest {

  @Test
  void should_create_result_with_content() {
    var result = new AgentResult("Spring AI is a framework for building AI applications");

    assertThat(result.content()).isEqualTo("Spring AI is a framework for building AI applications");
  }

  @Test
  void should_allow_empty_content() {
    var result = new AgentResult("");

    assertThat(result.content()).isEmpty();
  }

  @Test
  void should_reject_null_content() {
    assertThatThrownBy(() -> new AgentResult(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("content cannot be null");
  }
}
