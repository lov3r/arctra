package cn.bitcss.arctra.execution;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link EventType}.
 *
 * @author lov3r
 */
class EventTypeTest {

  @Test
  void eventType_hasExpectedInitialVocabulary() {
    EventType[] values = EventType.values();

    // M6-T1 initial vocabulary: exactly 11 event types
    assertThat(values).hasSize(11);

    // Verify expected types exist
    assertThat(values)
        .contains(
            EventType.PROCESS_STARTED,
            EventType.APPROVAL_REQUIRED,
            EventType.APPROVAL_GRANTED,
            EventType.APPROVAL_REJECTED,
            EventType.SUSPENDED,
            EventType.RESUMED,
            EventType.TOOL_EXECUTED,
            EventType.TOOL_FAILED,
            EventType.CHECKPOINT_CONFLICT,
            EventType.COMPLETED,
            EventType.FAILED);
  }

  @Test
  void eventType_valuesAreNotNull() {
    for (EventType type : EventType.values()) {
      assertThat(type).isNotNull();
      assertThat(type.name()).isNotBlank();
    }
  }
}
