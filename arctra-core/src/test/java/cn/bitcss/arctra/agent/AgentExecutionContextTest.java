package cn.bitcss.arctra.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("AgentExecutionContext")
class AgentExecutionContextTest {

  @Test
  @DisplayName("stateless() creates context with null sessionId")
  void stateless() {
    AgentExecutionContext context = AgentExecutionContext.stateless();

    assertThat(context.sessionId()).isNull();
  }

  @Test
  @DisplayName("withSession() creates context with given sessionId")
  void withSession() {
    AgentExecutionContext context = AgentExecutionContext.withSession("session-123");

    assertThat(context.sessionId()).isEqualTo("session-123");
  }

  @Test
  @DisplayName("withSession() rejects null sessionId")
  void withSessionRejectsNull() {
    assertThatThrownBy(() -> AgentExecutionContext.withSession(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("sessionId cannot be null or blank");
  }

  @Test
  @DisplayName("withSession() rejects blank sessionId")
  void withSessionRejectsBlank() {
    assertThatThrownBy(() -> AgentExecutionContext.withSession(""))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("sessionId cannot be null or blank");

    assertThatThrownBy(() -> AgentExecutionContext.withSession("   "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("sessionId cannot be null or blank");
  }

  @Test
  @DisplayName("Record equality works correctly")
  void recordEquality() {
    AgentExecutionContext context1 = AgentExecutionContext.withSession("session-123");
    AgentExecutionContext context2 = AgentExecutionContext.withSession("session-123");
    AgentExecutionContext context3 = AgentExecutionContext.withSession("session-456");

    assertThat(context1).isEqualTo(context2);
    assertThat(context1).isNotEqualTo(context3);
  }

  @Test
  @DisplayName("Stateless contexts are equal")
  void statelessEquality() {
    AgentExecutionContext context1 = AgentExecutionContext.stateless();
    AgentExecutionContext context2 = AgentExecutionContext.stateless();

    assertThat(context1).isEqualTo(context2);
  }
}
