package cn.bitcss.arctra.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cn.bitcss.arctra.agent.AgentDefinition;
import cn.bitcss.arctra.agent.AgentRequest;
import cn.bitcss.arctra.agent.AgentResult;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link AgentExecutionEngine} contract.
 *
 * <p>Verifies that different engine implementations can be used interchangeably.
 *
 * @author lov3r
 */
class AgentExecutionEngineTest {

  @Test
  void fake_engine_should_execute_and_return_result() {
    var engine = new FakeExecutionEngine();
    var definition = new AgentDefinition("test", "A test agent");
    var request = new AgentRequest("Test message");

    var result = engine.execute(definition, request);

    assertThat(result).isNotNull();
    assertThat(result.content()).contains("Test message");
  }

  @Test
  void fake_engine_should_handle_different_requests() {
    var engine = new FakeExecutionEngine();
    var definition = new AgentDefinition("test", "desc");

    var result1 = engine.execute(definition, new AgentRequest("First"));
    var result2 = engine.execute(definition, new AgentRequest("Second"));

    assertThat(result1.content()).contains("First");
    assertThat(result2.content()).contains("Second");
  }

  @Test
  void echo_engine_should_echo_user_message() {
    var engine = new EchoExecutionEngine();
    var definition = new AgentDefinition("test", "desc");
    var request = new AgentRequest("Hello World");

    var result = engine.execute(definition, request);

    assertThat(result.content()).isEqualTo("Echo: Hello World");
  }

  @Test
  void uppercase_engine_should_convert_to_uppercase() {
    var engine = new UpperCaseExecutionEngine();
    var definition = new AgentDefinition("test", "desc");
    var request = new AgentRequest("hello world");

    var result = engine.execute(definition, request);

    assertThat(result.content()).isEqualTo("HELLO WORLD");
  }
}
