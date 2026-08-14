package cn.bitcss.arctra.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cn.bitcss.arctra.agent.AgentDefinition;
import cn.bitcss.arctra.agent.AgentRequest;
import cn.bitcss.arctra.agent.AgentResult;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link AgentRuntime} contract using {@link FakeAgentRuntime}.
 *
 * <p>Verifies the minimal call path: runtime.execute(definition, request) → result.
 *
 * @author lov3r
 */
class AgentRuntimeTest {

  @Test
  void should_execute_agent_and_return_result() {
    AgentRuntime runtime = new FakeAgentRuntime();
    AgentDefinition definition = new AgentDefinition("test-agent", "A test agent");
    AgentRequest request = new AgentRequest("Hello, agent!");

    AgentResult result = runtime.execute(definition, request);

    assertThat(result).isNotNull();
    assertThat(result.content()).isNotEmpty();
    assertThat(result.content()).contains("Hello, agent!");
  }

  @Test
  void should_handle_different_requests() {
    AgentRuntime runtime = new FakeAgentRuntime();
    AgentDefinition definition = new AgentDefinition("test", "desc");

    AgentResult result1 = runtime.execute(definition, new AgentRequest("First message"));
    AgentResult result2 = runtime.execute(definition, new AgentRequest("Second message"));

    assertThat(result1.content()).contains("First message");
    assertThat(result2.content()).contains("Second message");
  }

  @Test
  void should_execute_with_minimal_definition() {
    AgentRuntime runtime = new FakeAgentRuntime();
    AgentDefinition definition = new AgentDefinition("minimal", null);
    AgentRequest request = new AgentRequest("Test");

    AgentResult result = runtime.execute(definition, request);

    assertThat(result).isNotNull();
    assertThat(result.content()).isNotEmpty();
  }
}
