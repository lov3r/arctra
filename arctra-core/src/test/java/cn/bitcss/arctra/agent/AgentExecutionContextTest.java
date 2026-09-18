package cn.bitcss.arctra.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.bitcss.arctra.durability.DurabilityMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("AgentExecutionContext")
class AgentExecutionContextTest {

  // M2 backward compatible tests

  @Test
  @DisplayName("stateless() creates ephemeral context with null sessionId")
  void stateless() {
    AgentExecutionContext context = AgentExecutionContext.stateless();

    assertThat(context.sessionId()).isNull();
    assertThat(context.durability()).isEqualTo(DurabilityMode.EPHEMERAL);
  }

  @Test
  @DisplayName("withSession() creates ephemeral context with given sessionId")
  void withSession() {
    AgentExecutionContext context = AgentExecutionContext.withSession("session-123");

    assertThat(context.sessionId()).isEqualTo("session-123");
    assertThat(context.durability()).isEqualTo(DurabilityMode.EPHEMERAL);
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

  // M6-T6.4 durable mode tests

  @Test
  @DisplayName("durableSession() creates durable context with sessionId")
  void durableSession() {
    AgentExecutionContext context = AgentExecutionContext.durableSession("durable-session");

    assertThat(context.sessionId()).isEqualTo("durable-session");
    assertThat(context.durability()).isEqualTo(DurabilityMode.DURABLE);
  }

  @Test
  @DisplayName("durableSession() allows null sessionId for stateless durable")
  void durableSessionAllowsNull() {
    AgentExecutionContext context = AgentExecutionContext.durableSession(null);

    assertThat(context.sessionId()).isNull();
    assertThat(context.durability()).isEqualTo(DurabilityMode.DURABLE);
  }

  @Test
  @DisplayName("durableSession() rejects blank sessionId")
  void durableSessionRejectsBlank() {
    assertThatThrownBy(() -> AgentExecutionContext.durableSession(""))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("sessionId cannot be blank");

    assertThatThrownBy(() -> AgentExecutionContext.durableSession("   "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("sessionId cannot be blank");
  }

  @Test
  @DisplayName("durableStateless() creates durable context with null sessionId")
  void durableStateless() {
    AgentExecutionContext context = AgentExecutionContext.durableStateless();

    assertThat(context.sessionId()).isNull();
    assertThat(context.durability()).isEqualTo(DurabilityMode.DURABLE);
  }

  @Test
  @DisplayName("Constructor rejects null durability")
  void constructorRejectsNullDurability() {
    assertThatThrownBy(() -> new AgentExecutionContext("session", null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("durability cannot be null");
  }

  @Test
  @DisplayName("Durability mode affects equality")
  void durabilityAffectsEquality() {
    AgentExecutionContext ephemeral = AgentExecutionContext.withSession("session");
    AgentExecutionContext durable = AgentExecutionContext.durableSession("session");

    assertThat(ephemeral).isNotEqualTo(durable);
    assertThat(ephemeral.sessionId()).isEqualTo(durable.sessionId());
    assertThat(ephemeral.durability()).isNotEqualTo(durable.durability());
  }
}
