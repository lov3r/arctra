package cn.bitcss.arctra.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AgentRequest}.
 *
 * @author lov3r
 */
class AgentRequestTest {

  @Test
  void should_create_request_with_valid_message() {
    var request = new AgentRequest("What is Spring AI?");

    assertThat(request.userMessage()).isEqualTo("What is Spring AI?");
  }

  @Test
  void should_reject_null_message() {
    assertThatThrownBy(() -> new AgentRequest(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("userMessage cannot be blank");
  }

  @Test
  void should_reject_empty_message() {
    assertThatThrownBy(() -> new AgentRequest(""))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("userMessage cannot be blank");
  }

  @Test
  void should_reject_blank_message() {
    assertThatThrownBy(() -> new AgentRequest("   "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("userMessage cannot be blank");
  }
}
