package cn.bitcss.arctra.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ExecutionRecord}.
 *
 * @author lov3r
 */
class ExecutionRecordTest {

  @Test
  void constructor_validRecord_succeeds() {
    ExecutionRecord record =
        new ExecutionRecord(
            "P100:1",
            "P100",
            1,
            EventType.PROCESS_STARTED,
            Instant.now(),
            null,
            null);

    assertThat(record.recordId()).isEqualTo("P100:1");
    assertThat(record.processId()).isEqualTo("P100");
    assertThat(record.sequence()).isEqualTo(1);
    assertThat(record.eventType()).isEqualTo(EventType.PROCESS_STARTED);
    assertThat(record.occurredAt()).isNotNull();
    assertThat(record.checkpointVersion()).isNull();
    assertThat(record.payload()).isNull();
  }

  @Test
  void constructor_withCheckpointVersion_succeeds() {
    ExecutionRecord record =
        new ExecutionRecord(
            "P100:5",
            "P100",
            5,
            EventType.SUSPENDED,
            Instant.now(),
            3L,
            """
            {"reason":"approval_required"}
            """);

    assertThat(record.checkpointVersion()).isEqualTo(3L);
    assertThat(record.payload()).contains("approval_required");
  }

  @Test
  void constructor_nullRecordId_throwsException() {
    assertThatThrownBy(
            () ->
                new ExecutionRecord(
                    null, "P100", 1, EventType.PROCESS_STARTED, Instant.now(), null, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("recordId cannot be null or blank");
  }

  @Test
  void constructor_blankRecordId_throwsException() {
    assertThatThrownBy(
            () ->
                new ExecutionRecord(
                    "", "P100", 1, EventType.PROCESS_STARTED, Instant.now(), null, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("recordId cannot be null or blank");
  }

  @Test
  void constructor_nullProcessId_throwsException() {
    assertThatThrownBy(
            () ->
                new ExecutionRecord(
                    "P100:1", null, 1, EventType.PROCESS_STARTED, Instant.now(), null, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("processId cannot be null or blank");
  }

  @Test
  void constructor_blankProcessId_throwsException() {
    assertThatThrownBy(
            () ->
                new ExecutionRecord(
                    "P100:1", "", 1, EventType.PROCESS_STARTED, Instant.now(), null, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("processId cannot be null or blank");
  }

  @Test
  void constructor_zeroSequence_throwsException() {
    assertThatThrownBy(
            () ->
                new ExecutionRecord(
                    "P100:0", "P100", 0, EventType.PROCESS_STARTED, Instant.now(), null, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("sequence must be positive");
  }

  @Test
  void constructor_negativeSequence_throwsException() {
    assertThatThrownBy(
            () ->
                new ExecutionRecord(
                    "P100:-1", "P100", -1, EventType.PROCESS_STARTED, Instant.now(), null, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("sequence must be positive");
  }

  @Test
  void constructor_nullEventType_throwsException() {
    assertThatThrownBy(
            () -> new ExecutionRecord("P100:1", "P100", 1, null, Instant.now(), null, null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("eventType cannot be null");
  }

  @Test
  void constructor_nullOccurredAt_throwsException() {
    assertThatThrownBy(
            () ->
                new ExecutionRecord(
                    "P100:1", "P100", 1, EventType.PROCESS_STARTED, null, null, null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("occurredAt cannot be null");
  }

  @Test
  void constructor_inconsistentRecordId_throwsException() {
    assertThatThrownBy(
            () ->
                new ExecutionRecord(
                    "P100:999",
                    "P100",
                    3,
                    EventType.PROCESS_STARTED,
                    Instant.now(),
                    null,
                    null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("recordId must equal processId:sequence")
        .hasMessageContaining("expected: P100:3")
        .hasMessageContaining("got: P100:999");
  }

  @Test
  void constructor_zeroCheckpointVersion_throwsException() {
    assertThatThrownBy(
            () ->
                new ExecutionRecord(
                    "P100:1", "P100", 1, EventType.SUSPENDED, Instant.now(), 0L, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("checkpointVersion must be positive when non-null");
  }

  @Test
  void constructor_negativeCheckpointVersion_throwsException() {
    assertThatThrownBy(
            () ->
                new ExecutionRecord(
                    "P100:1", "P100", 1, EventType.SUSPENDED, Instant.now(), -1L, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("checkpointVersion must be positive when non-null");
  }

  @Test
  void constructor_nullCheckpointVersion_allowed() {
    // Null checkpointVersion is valid - not all events are checkpointed
    ExecutionRecord record =
        new ExecutionRecord(
            "P100:1",
            "P100",
            1,
            EventType.TOOL_EXECUTED,
            Instant.now(),
            null,
            null);

    assertThat(record.checkpointVersion()).isNull();
  }

  @Test
  void constructor_nullPayload_allowed() {
    // Null payload is valid - some events have no additional data
    ExecutionRecord record =
        new ExecutionRecord(
            "P100:1",
            "P100",
            1,
            EventType.PROCESS_STARTED,
            Instant.now(),
            null,
            null);

    assertThat(record.payload()).isNull();
  }
}
