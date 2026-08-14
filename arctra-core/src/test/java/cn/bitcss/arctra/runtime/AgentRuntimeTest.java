package cn.bitcss.arctra.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.bitcss.arctra.agent.AgentDefinition;
import cn.bitcss.arctra.agent.AgentRequest;
import cn.bitcss.arctra.agent.AgentResult;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link AgentRuntime} contract using {@link DefaultAgentRuntime}.
 *
 * <p>Verifies that Runtime delegates to Engine and that engines are replaceable.
 *
 * @author lov3r
 */
class AgentRuntimeTest {

  @Test
  void should_delegate_to_fake_engine() {
    var engine = new FakeExecutionEngine();
    var runtime = new DefaultAgentRuntime(engine);
    var definition = new AgentDefinition("test-agent", "A test agent");
    var request = new AgentRequest("Hello, agent!");

    var result = runtime.execute(definition, request);

    assertThat(result).isNotNull();
    assertThat(result.content()).contains("Hello, agent!");
  }

  @Test
  void should_handle_different_requests_with_same_engine() {
    var engine = new FakeExecutionEngine();
    var runtime = new DefaultAgentRuntime(engine);
    var definition = new AgentDefinition("test", "desc");

    var result1 = runtime.execute(definition, new AgentRequest("First message"));
    var result2 = runtime.execute(definition, new AgentRequest("Second message"));

    assertThat(result1.content()).contains("First message");
    assertThat(result2.content()).contains("Second message");
  }

  @Test
  void should_execute_with_minimal_definition() {
    var engine = new FakeExecutionEngine();
    var runtime = new DefaultAgentRuntime(engine);
    var definition = new AgentDefinition("minimal", null);
    var request = new AgentRequest("Test");

    var result = runtime.execute(definition, request);

    assertThat(result).isNotNull();
    assertThat(result.content()).isNotEmpty();
  }

  @Test
  void should_support_different_engine_without_runtime_change() {
    var definition = new AgentDefinition("test", "desc");
    var request = new AgentRequest("test");

    // Same runtime implementation, different engines
    var runtimeWithFake = new DefaultAgentRuntime(new FakeExecutionEngine());
    var runtimeWithEcho = new DefaultAgentRuntime(new EchoExecutionEngine());
    var runtimeWithUpperCase = new DefaultAgentRuntime(new UpperCaseExecutionEngine());

    var resultFake = runtimeWithFake.execute(definition, request);
    var resultEcho = runtimeWithEcho.execute(definition, request);
    var resultUpperCase = runtimeWithUpperCase.execute(definition, request);

    // Different engines produce different behaviors
    assertThat(resultFake.content()).startsWith("Fake response");
    assertThat(resultEcho.content()).isEqualTo("Echo: test");
    assertThat(resultUpperCase.content()).isEqualTo("TEST");
  }

  @Test
  void should_reject_null_engine() {
    assertThatThrownBy(() -> new DefaultAgentRuntime(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("engine cannot be null");
  }
}
