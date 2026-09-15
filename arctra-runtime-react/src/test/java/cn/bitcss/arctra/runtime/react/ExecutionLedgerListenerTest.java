package cn.bitcss.arctra.runtime.react;

import static org.assertj.core.api.Assertions.*;

import cn.bitcss.arctra.execution.EventType;
import cn.bitcss.arctra.execution.ExecutionEvent;
import cn.bitcss.arctra.execution.InMemoryExecutionLedger;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ExecutionLedgerListener}.
 *
 * @author lov3r
 */
class ExecutionLedgerListenerTest {

  @Test
  void projectEventToLedger() {
    var ledger = new InMemoryExecutionLedger();
    var listener = new ExecutionLedgerListener(ledger);

    var event =
        new ExecutionEvent("process-123", EventType.SUSPENDED, 1L, """
            {"key": "value"}
            """);

    listener.onEvent(event);

    var records = ledger.queryByProcess("process-123");
    assertThat(records).hasSize(1);

    var record = records.get(0);
    assertThat(record.processId()).isEqualTo("process-123");
    assertThat(record.eventType()).isEqualTo(EventType.SUSPENDED);
    assertThat(record.checkpointVersion()).isEqualTo(1L);
    assertThat(record.payload()).contains("key");
  }

  @Test
  void ledgerAssignsSequenceRecordIdOccurredAt() {
    var ledger = new InMemoryExecutionLedger();
    var listener = new ExecutionLedgerListener(ledger);

    var event = new ExecutionEvent("process-123", EventType.COMPLETED, 5L, null);

    listener.onEvent(event);

    var records = ledger.queryByProcess("process-123");
    var record = records.get(0);

    // Ledger assigns sequence
    assertThat(record.sequence()).isEqualTo(1);

    // Ledger derives recordId
    assertThat(record.recordId()).isEqualTo("process-123:1");

    // Ledger assigns occurredAt
    assertThat(record.occurredAt()).isNotNull();
  }

  @Test
  void multipleEvents() {
    var ledger = new InMemoryExecutionLedger();
    var listener = new ExecutionLedgerListener(ledger);

    listener.onEvent(new ExecutionEvent("process-123", EventType.APPROVAL_REQUIRED, null, null));
    listener.onEvent(new ExecutionEvent("process-123", EventType.SUSPENDED, 1L, null));
    listener.onEvent(new ExecutionEvent("process-123", EventType.APPROVAL_GRANTED, 1L, null));

    var records = ledger.queryByProcess("process-123");
    assertThat(records).hasSize(3);
    assertThat(records.get(0).eventType()).isEqualTo(EventType.APPROVAL_REQUIRED);
    assertThat(records.get(1).eventType()).isEqualTo(EventType.SUSPENDED);
    assertThat(records.get(2).eventType()).isEqualTo(EventType.APPROVAL_GRANTED);
  }

  @Test
  void nullCheckpointVersionPreserved() {
    var ledger = new InMemoryExecutionLedger();
    var listener = new ExecutionLedgerListener(ledger);

    var event = new ExecutionEvent("process-123", EventType.APPROVAL_REQUIRED, null, null);
    listener.onEvent(event);

    var record = ledger.queryByProcess("process-123").get(0);
    assertThat(record.checkpointVersion()).isNull();
  }

  @Test
  void nullPayloadPreserved() {
    var ledger = new InMemoryExecutionLedger();
    var listener = new ExecutionLedgerListener(ledger);

    var event = new ExecutionEvent("process-123", EventType.COMPLETED, 3L, null);
    listener.onEvent(event);

    var record = ledger.queryByProcess("process-123").get(0);
    assertThat(record.payload()).isNull();
  }
}
