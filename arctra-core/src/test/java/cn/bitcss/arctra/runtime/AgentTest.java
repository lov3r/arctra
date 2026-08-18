package cn.bitcss.arctra.runtime;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.bitcss.arctra.agent.Agent;
import cn.bitcss.arctra.agent.AgentDefinition;
import cn.bitcss.arctra.agent.AgentExecutionContext;
import cn.bitcss.arctra.agent.AgentRequest;
import cn.bitcss.arctra.agent.AgentResult;
import cn.bitcss.arctra.evidence.Evidence;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("Agent API")
class AgentTest {

  private AgentExecutionEngine engine;
  private AgentRuntime runtime;

  @BeforeEach
  void setup() {
    engine = mock(AgentExecutionEngine.class);
    runtime = new DefaultAgentRuntime(engine);
  }

  @Nested
  @DisplayName("Agent Binding")
  class AgentBindingTests {

    @Test
    @DisplayName("runtime.agent(definition) returns Agent handle")
    void agentCreation() {
      // Arrange
      var definition = new AgentDefinition("test", "desc");

      // Act
      Agent agent = runtime.agent(definition);

      // Assert
      assertThat(agent).isNotNull();
    }

    @Test
    @DisplayName("runtime.agent(null) throws NPE")
    void agentCreationWithNullDefinition() {
      // Act & Assert
      assertThatThrownBy(() -> runtime.agent(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("definition");
    }
  }

  @Nested
  @DisplayName("Stateless Invocation")
  class StatelessInvocationTests {

    @Test
    @DisplayName("agent.execute(request) delegates to engine with stateless context")
    void statelessExecution() {
      // Arrange
      var definition = new AgentDefinition("test", "desc");
      var request = new AgentRequest("message");
      var expectedResult = new AgentResult("response", List.of());

      when(engine.execute(definition, request, AgentExecutionContext.stateless()))
          .thenReturn(expectedResult);

      Agent agent = runtime.agent(definition);

      // Act
      var result = agent.execute(request);

      // Assert
      assertThat(result).isSameAs(expectedResult);
      verify(engine).execute(definition, request, AgentExecutionContext.stateless());
    }

    @Test
    @DisplayName("agent.execute(null) throws NPE")
    void statelessExecutionWithNullRequest() {
      // Arrange
      Agent agent = runtime.agent(new AgentDefinition("test", "desc"));

      // Act & Assert
      assertThatThrownBy(() -> agent.execute(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("request");
    }
  }

  @Nested
  @DisplayName("Stateful Invocation")
  class StatefulInvocationTests {

    @Test
    @DisplayName("agent.execute(request, context) delegates to engine with context")
    void statefulExecution() {
      // Arrange
      var definition = new AgentDefinition("test", "desc");
      var request = new AgentRequest("message");
      var context = AgentExecutionContext.withSession("session-123");
      var expectedResult = new AgentResult("response", List.of());

      when(engine.execute(definition, request, context)).thenReturn(expectedResult);

      Agent agent = runtime.agent(definition);

      // Act
      var result = agent.execute(request, context);

      // Assert
      assertThat(result).isSameAs(expectedResult);
      verify(engine).execute(definition, request, context);
    }

    @Test
    @DisplayName("agent.execute(request, null context) throws NPE")
    void statefulExecutionWithNullContext() {
      // Arrange
      Agent agent = runtime.agent(new AgentDefinition("test", "desc"));
      var request = new AgentRequest("message");

      // Act & Assert
      assertThatThrownBy(() -> agent.execute(request, null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("context");
    }
  }

  @Nested
  @DisplayName("Definition Binding")
  class DefinitionBindingTests {

    @Test
    @DisplayName("Agent handle uses bound definition")
    void boundDefinitionUsed() {
      // Arrange
      var definition = new AgentDefinition("test-agent", "test description");
      var request = new AgentRequest("message");
      var context = AgentExecutionContext.stateless();

      when(engine.execute(definition, request, context))
          .thenReturn(new AgentResult("response", List.of()));

      Agent agent = runtime.agent(definition);

      // Act
      agent.execute(request, context);

      // Assert - verify engine was called with exact definition
      verify(engine).execute(definition, request, context);
    }
  }

  @Nested
  @DisplayName("Multiple Agent Handles")
  class MultipleAgentHandlesTests {

    @Test
    @DisplayName("Different agent handles use different definitions")
    void multipleAgentsWithDifferentDefinitions() {
      // Arrange
      var defA = new AgentDefinition("agent-A", "desc A");
      var defB = new AgentDefinition("agent-B", "desc B");
      var request = new AgentRequest("message");
      var context = AgentExecutionContext.stateless();

      when(engine.execute(any(), any(), any())).thenReturn(new AgentResult("response", List.of()));

      Agent agentA = runtime.agent(defA);
      Agent agentB = runtime.agent(defB);

      // Act
      agentA.execute(request, context);
      agentB.execute(request, context);

      // Assert - verify each agent used its own definition
      verify(engine).execute(defA, request, context);
      verify(engine).execute(defB, request, context);
    }
  }

  @Nested
  @DisplayName("Reusable Agent Handle")
  class ReusableAgentHandleTests {

    @Test
    @DisplayName("Same agent handle can be executed multiple times")
    void reusableAgentHandle() {
      // Arrange
      var definition = new AgentDefinition("test", "desc");
      var request1 = new AgentRequest("message 1");
      var request2 = new AgentRequest("message 2");
      var context1 = AgentExecutionContext.stateless();
      var context2 = AgentExecutionContext.withSession("session-A");

      when(engine.execute(any(), any(), any())).thenReturn(new AgentResult("response", List.of()));

      Agent agent = runtime.agent(definition);

      // Act - multiple invocations of same handle
      agent.execute(request1, context1);
      agent.execute(request2, context2);

      // Assert - verify agent is reusable
      verify(engine).execute(definition, request1, context1);
      verify(engine).execute(definition, request2, context2);
    }

    @Test
    @DisplayName("Agent handle has no mutable state")
    void agentHandleStateless() {
      // Arrange
      var definition = new AgentDefinition("test", "desc");
      var request = new AgentRequest("message");

      when(engine.execute(any(), any(), any())).thenReturn(new AgentResult("response 1", List.of()));

      Agent agent = runtime.agent(definition);

      // Act - first execution
      var result1 = agent.execute(request);

      // Arrange - change engine behavior
      when(engine.execute(any(), any(), any()))
          .thenReturn(new AgentResult("response 2", List.of()));

      // Act - second execution
      var result2 = agent.execute(request);

      // Assert - agent doesn't cache results, delegates each time
      assertThat(result1.content()).isEqualTo("response 1");
      assertThat(result2.content()).isEqualTo("response 2");
      verify(engine, times(2)).execute(any(), any(), any());
    }
  }

  @Nested
  @DisplayName("AgentRuntime Direct Execution (Low-Level API)")
  class RuntimeDirectExecutionTests {

    @Test
    @DisplayName("runtime.execute(def, req, ctx) works")
    void runtimeDirectExecution() {
      // Arrange
      var definition = new AgentDefinition("test", "desc");
      var request = new AgentRequest("message");
      var context = AgentExecutionContext.withSession("session-A");
      var expectedResult = new AgentResult("response", List.of());

      when(engine.execute(definition, request, context)).thenReturn(expectedResult);

      // Act
      var result = runtime.execute(definition, request, context);

      // Assert
      assertThat(result).isSameAs(expectedResult);
      verify(engine).execute(definition, request, context);
    }

    @Test
    @DisplayName("runtime.execute(def, req) uses stateless context")
    void runtimeDirectExecutionStateless() {
      // Arrange
      var definition = new AgentDefinition("test", "desc");
      var request = new AgentRequest("message");
      var expectedResult = new AgentResult("response", List.of());

      when(engine.execute(definition, request, AgentExecutionContext.stateless()))
          .thenReturn(expectedResult);

      // Act
      var result = runtime.execute(definition, request);

      // Assert
      assertThat(result).isSameAs(expectedResult);
      verify(engine).execute(definition, request, AgentExecutionContext.stateless());
    }
  }
}
