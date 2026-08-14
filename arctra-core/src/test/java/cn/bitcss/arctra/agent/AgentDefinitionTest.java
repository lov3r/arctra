package cn.bitcss.arctra.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AgentDefinition}.
 *
 * @author lov3r
 */
class AgentDefinitionTest {

  @Test
  void should_create_agent_definition_with_valid_inputs() {
    var definition = new AgentDefinition("knowledge-agent", "Answers questions about the project");

    assertThat(definition.name()).isEqualTo("knowledge-agent");
    assertThat(definition.description()).isEqualTo("Answers questions about the project");
  }

  @Test
  void should_allow_null_description() {
    var definition = new AgentDefinition("test-agent", null);

    assertThat(definition.name()).isEqualTo("test-agent");
    assertThat(definition.description()).isNull();
  }

  @Test
  void should_reject_null_name() {
    assertThatThrownBy(() -> new AgentDefinition(null, "description"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("name cannot be blank");
  }

  @Test
  void should_reject_empty_name() {
    assertThatThrownBy(() -> new AgentDefinition("", "description"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("name cannot be blank");
  }

  @Test
  void should_reject_blank_name() {
    assertThatThrownBy(() -> new AgentDefinition("   ", "description"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("name cannot be blank");
  }
}
