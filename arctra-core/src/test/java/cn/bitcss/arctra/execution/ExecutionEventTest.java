package cn.bitcss.arctra.execution;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ExecutionEvent}.
 *
 * @author lov3r
 */
class ExecutionEventTest {

  @Test
  void validConstruction() {
    var event =
        new ExecutionEvent("process-123", EventType.SUSPENDED, 1L, """
            {"key": "value"}
            """);

    assertThat(event.processId()).isEqualTo("process-123");
    assertThat(event.eventType()).isEqualTo(EventType.SUSPENDED);
    assertThat(event.checkpointVersion()).isEqualTo(1L);
    assertThat(event.payload()).contains("key");
  }

  @Test
  void nullableCheckpointVersion() {
    var event = new ExecutionEvent("process-123", EventType.APPROVAL_REQUIRED, null, null);

    assertThat(event.processId()).isEqualTo("process-123");
    assertThat(event.eventType()).isEqualTo(EventType.APPROVAL_REQUIRED);
    assertThat(event.checkpointVersion()).isNull();
    assertThat(event.payload()).isNull();
  }

  @Test
  void nullablePayload() {
    var event = new ExecutionEvent("process-123", EventType.COMPLETED, 5L, null);

    assertThat(event.payload()).isNull();
  }

  @Test
  void rejectNullProcessId() {
    assertThatThrownBy(() -> new ExecutionEvent(null, EventType.SUSPENDED, 1L, null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("processId cannot be null");
  }

  @Test
  void rejectBlankProcessId() {
    assertThatThrownBy(() -> new ExecutionEvent("", EventType.SUSPENDED, 1L, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("processId cannot be blank");

    assertThatThrownBy(() -> new ExecutionEvent("   ", EventType.SUSPENDED, 1L, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("processId cannot be blank");
  }

  @Test
  void rejectNullEventType() {
    assertThatThrownBy(() -> new ExecutionEvent("process-123", null, 1L, null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("eventType cannot be null");
  }

  @Test
  void rejectZeroCheckpointVersion() {
    assertThatThrownBy(() -> new ExecutionEvent("process-123", EventType.SUSPENDED, 0L, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("checkpointVersion must be positive when non-null");
  }

  @Test
  void rejectNegativeCheckpointVersion() {
    assertThatThrownBy(() -> new ExecutionEvent("process-123", EventType.SUSPENDED, -1L, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("checkpointVersion must be positive when non-null");
  }

  @Test
  void recordEquality() {
    var event1 = new ExecutionEvent("process-123", EventType.SUSPENDED, 1L, "payload");
    var event2 = new ExecutionEvent("process-123", EventType.SUSPENDED, 1L, "payload");
    var event3 = new ExecutionEvent("process-999", EventType.SUSPENDED, 1L, "payload");

    assertThat(event1).isEqualTo(event2);
    assertThat(event1).isNotEqualTo(event3);
    assertThat(event1.hashCode()).isEqualTo(event2.hashCode());
  }
}
