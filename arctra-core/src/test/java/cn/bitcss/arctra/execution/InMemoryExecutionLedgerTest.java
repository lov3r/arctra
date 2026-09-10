package cn.bitcss.arctra.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link InMemoryExecutionLedger}.
 *
 * @author lov3r
 */
class InMemoryExecutionLedgerTest {

  @Test
  void append_firstRecord_hasSequence1() {
    ExecutionLedger ledger = new InMemoryExecutionLedger();
    ExecutionRecord record = ledger.append("P1", EventType.PROCESS_STARTED, null, null);

    assertThat(record.sequence()).isEqualTo(1);
    assertThat(record.recordId()).isEqualTo("P1:1");
    assertThat(record.processId()).isEqualTo("P1");
    assertThat(record.eventType()).isEqualTo(EventType.PROCESS_STARTED);
    assertThat(record.occurredAt()).isNotNull();
  }

  @Test
  void append_multipleRecords_sequencesIncrease() {
    ExecutionLedger ledger = new InMemoryExecutionLedger();

    ExecutionRecord r1 = ledger.append("P1", EventType.PROCESS_STARTED, null, null);
    ExecutionRecord r2 = ledger.append("P1", EventType.TOOL_EXECUTED, 1L, null);
    ExecutionRecord r3 = ledger.append("P1", EventType.COMPLETED, 1L, null);

    // Sequences are monotonically increasing
    assertThat(r2.sequence()).isGreaterThan(r1.sequence());
    assertThat(r3.sequence()).isGreaterThan(r2.sequence());
  }

  @Test
  void append_nullProcessId_throwsException() {
    ExecutionLedger ledger = new InMemoryExecutionLedger();

    assertThatThrownBy(() -> ledger.append(null, EventType.PROCESS_STARTED, null, null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("processId cannot be null");
  }

  @Test
  void append_nullEventType_throwsException() {
    ExecutionLedger ledger = new InMemoryExecutionLedger();

    assertThatThrownBy(() -> ledger.append("P1", null, null, null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("eventType cannot be null");
  }

  @Test
  void append_invalidCheckpointVersion_throwsException() {
    ExecutionLedger ledger = new InMemoryExecutionLedger();

    assertThatThrownBy(() -> ledger.append("P1", EventType.SUSPENDED, 0L, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("checkpointVersion must be positive when non-null");

    assertThatThrownBy(() -> ledger.append("P1", EventType.SUSPENDED, -1L, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("checkpointVersion must be positive when non-null");
  }

  @Test
  void append_withCheckpointVersionAndPayload_succeeds() {
    ExecutionLedger ledger = new InMemoryExecutionLedger();

    ExecutionRecord record =
        ledger.append("P1", EventType.SUSPENDED, 3L, """
        {"reason":"approval_required"}
        """);

    assertThat(record.checkpointVersion()).isEqualTo(3L);
    assertThat(record.payload()).contains("approval_required");
  }

  @Test
  void queryByProcess_returnsOrderedBySequenceAscending() {
    ExecutionLedger ledger = new InMemoryExecutionLedger();

    ledger.append("P1", EventType.PROCESS_STARTED, null, null);
    ledger.append("P1", EventType.TOOL_EXECUTED, 1L, null);
    ledger.append("P1", EventType.COMPLETED, 1L, null);

    List<ExecutionRecord> records = ledger.queryByProcess("P1");

    assertThat(records).hasSize(3);
    // Verify ascending sequence order
    assertThat(records.get(0).eventType()).isEqualTo(EventType.PROCESS_STARTED);
    assertThat(records.get(1).eventType()).isEqualTo(EventType.TOOL_EXECUTED);
    assertThat(records.get(2).eventType()).isEqualTo(EventType.COMPLETED);

    // Verify strictly increasing sequences
    assertThat(records.get(1).sequence()).isGreaterThan(records.get(0).sequence());
    assertThat(records.get(2).sequence()).isGreaterThan(records.get(1).sequence());
  }

  @Test
  void queryByProcess_unknownProcessId_returnsEmpty() {
    ExecutionLedger ledger = new InMemoryExecutionLedger();

    ledger.append("P1", EventType.PROCESS_STARTED, null, null);

    List<ExecutionRecord> records = ledger.queryByProcess("P2");

    assertThat(records).isEmpty();
  }

  @Test
  void queryByProcess_nullProcessId_throwsException() {
    ExecutionLedger ledger = new InMemoryExecutionLedger();

    assertThatThrownBy(() -> ledger.queryByProcess(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("processId cannot be null");
  }

  @Test
  void queryRecentByProcess_returnsDescendingOrder() {
    ExecutionLedger ledger = new InMemoryExecutionLedger();

    ledger.append("P1", EventType.PROCESS_STARTED, null, null);
    ledger.append("P1", EventType.TOOL_EXECUTED, 1L, null);
    ledger.append("P1", EventType.COMPLETED, 1L, null);

    List<ExecutionRecord> recent = ledger.queryRecentByProcess("P1", 2);

    assertThat(recent).hasSize(2);
    // Verify descending sequence order (newest first)
    assertThat(recent.get(0).eventType()).isEqualTo(EventType.COMPLETED);
    assertThat(recent.get(1).eventType()).isEqualTo(EventType.TOOL_EXECUTED);
    assertThat(recent.get(0).sequence()).isGreaterThan(recent.get(1).sequence());
  }

  @Test
  void queryRecentByProcess_limitExceedsTotal_returnsAll() {
    ExecutionLedger ledger = new InMemoryExecutionLedger();

    ledger.append("P1", EventType.PROCESS_STARTED, null, null);
    ledger.append("P1", EventType.COMPLETED, null, null);

    List<ExecutionRecord> recent = ledger.queryRecentByProcess("P1", 10);

    assertThat(recent).hasSize(2);
  }

  @Test
  void queryRecentByProcess_nullProcessId_throwsException() {
    ExecutionLedger ledger = new InMemoryExecutionLedger();

    assertThatThrownBy(() -> ledger.queryRecentByProcess(null, 5))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("processId cannot be null");
  }

  @Test
  void queryRecentByProcess_invalidLimit_throwsException() {
    ExecutionLedger ledger = new InMemoryExecutionLedger();

    assertThatThrownBy(() -> ledger.queryRecentByProcess("P1", 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("limit must be positive");

    assertThatThrownBy(() -> ledger.queryRecentByProcess("P1", -1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("limit must be positive");
  }

  @Test
  void differentProcesses_haveIndependentSequences() {
    ExecutionLedger ledger = new InMemoryExecutionLedger();

    ExecutionRecord p1r1 = ledger.append("P1", EventType.PROCESS_STARTED, null, null);
    ExecutionRecord p2r1 = ledger.append("P2", EventType.PROCESS_STARTED, null, null);
    ExecutionRecord p1r2 = ledger.append("P1", EventType.COMPLETED, null, null);

    // Each process has independent sequence numbering
    assertThat(p1r1.sequence()).isEqualTo(1);
    assertThat(p2r1.sequence()).isEqualTo(1); // P2 also starts at 1
    assertThat(p1r2.sequence()).isEqualTo(2);

    // Verify independence via queries
    List<ExecutionRecord> p1Records = ledger.queryByProcess("P1");
    List<ExecutionRecord> p2Records = ledger.queryByProcess("P2");

    assertThat(p1Records).hasSize(2);
    assertThat(p2Records).hasSize(1);
    assertThat(p1Records.get(0).sequence()).isEqualTo(1);
    assertThat(p2Records.get(0).sequence()).isEqualTo(1);
  }

  @Test
  void append_successfulAppend_immediatelyQueryable() {
    ExecutionLedger ledger = new InMemoryExecutionLedger();

    ExecutionRecord appended = ledger.append("P1", EventType.TOOL_EXECUTED, null, null);

    // Successful append must be immediately queryable from same ledger instance
    List<ExecutionRecord> records = ledger.queryByProcess("P1");

    assertThat(records).hasSize(1);
    assertThat(records.get(0).recordId()).isEqualTo(appended.recordId());
    assertThat(records.get(0).sequence()).isEqualTo(appended.sequence());
  }
}
