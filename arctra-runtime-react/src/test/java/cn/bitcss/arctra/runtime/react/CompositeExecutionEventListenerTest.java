package cn.bitcss.arctra.runtime.react;

import static org.assertj.core.api.Assertions.*;

import cn.bitcss.arctra.execution.EventType;
import cn.bitcss.arctra.execution.ExecutionEvent;
import cn.bitcss.arctra.execution.ExecutionEventListener;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CompositeExecutionEventListener}.
 *
 * @author lov3r
 */
class CompositeExecutionEventListenerTest {

  @Test
  void fanOutToMultipleListeners() {
    List<ExecutionEvent> received1 = new ArrayList<>();
    List<ExecutionEvent> received2 = new ArrayList<>();
    List<ExecutionEvent> received3 = new ArrayList<>();

    var composite =
        new CompositeExecutionEventListener(
            List.of(
                event -> received1.add(event),
                event -> received2.add(event),
                event -> received3.add(event)));

    var event = new ExecutionEvent("process-123", EventType.SUSPENDED, 1L, null);
    composite.onEvent(event);

    assertThat(received1).containsExactly(event);
    assertThat(received2).containsExactly(event);
    assertThat(received3).containsExactly(event);
  }

  @Test
  void deterministicInvocationOrder() {
    List<String> invocationOrder = new ArrayList<>();

    var composite =
        new CompositeExecutionEventListener(
            List.of(
                event -> invocationOrder.add("A"),
                event -> invocationOrder.add("B"),
                event -> invocationOrder.add("C")));

    var event = new ExecutionEvent("process-123", EventType.COMPLETED, 5L, null);
    composite.onEvent(event);

    assertThat(invocationOrder).containsExactly("A", "B", "C");
  }

  @Test
  void isolateFailure_listenerBStillReceivesEvent() {
    List<String> successfulInvocations = new ArrayList<>();

    ExecutionEventListener listenerA = event -> successfulInvocations.add("A");
    ExecutionEventListener listenerB =
        event -> {
          throw new RuntimeException("B failed");
        };
    ExecutionEventListener listenerC = event -> successfulInvocations.add("C");

    var composite = new CompositeExecutionEventListener(List.of(listenerA, listenerB, listenerC));

    var event = new ExecutionEvent("process-123", EventType.RESUMED, 2L, null);

    // Does not throw - failure isolated
    assertThatCode(() -> composite.onEvent(event)).doesNotThrowAnyException();

    // Listener C still executed despite B failure
    assertThat(successfulInvocations).containsExactly("A", "C");
  }

  @Test
  void multipleFailures_remainingListenersContinue() {
    List<String> successfulInvocations = new ArrayList<>();

    var composite =
        new CompositeExecutionEventListener(
            List.of(
                event -> {
                  throw new RuntimeException("1 failed");
                },
                event -> successfulInvocations.add("2"),
                event -> {
                  throw new RuntimeException("3 failed");
                },
                event -> successfulInvocations.add("4")));

    var event = new ExecutionEvent("process-123", EventType.APPROVAL_GRANTED, 3L, null);
    composite.onEvent(event);

    assertThat(successfulInvocations).containsExactly("2", "4");
  }

  @Test
  void emptyListeners_noOp() {
    var composite = new CompositeExecutionEventListener(List.of());

    var event = new ExecutionEvent("process-123", EventType.SUSPENDED, 1L, null);

    assertThatCode(() -> composite.onEvent(event)).doesNotThrowAnyException();
  }

  @Test
  void singleListener() {
    List<ExecutionEvent> received = new ArrayList<>();

    var composite = new CompositeExecutionEventListener(List.of(event -> received.add(event)));

    var event = new ExecutionEvent("process-123", EventType.CHECKPOINT_CONFLICT, 2L, null);
    composite.onEvent(event);

    assertThat(received).containsExactly(event);
  }
}
