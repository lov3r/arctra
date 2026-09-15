package cn.bitcss.arctra.runtime.react;

import cn.bitcss.arctra.execution.ExecutionEvent;
import cn.bitcss.arctra.execution.ExecutionEventListener;
import java.util.List;
import java.util.Objects;

/**
 * Composite execution event listener that fans out to multiple listeners.
 *
 * <p>Invokes listeners synchronously in registration order. Listener failures are isolated - one
 * listener exception does not suppress other listeners.
 *
 * <p><strong>Failure Isolation Semantics:</strong>
 *
 * <p>If listener A throws, listener B still receives the event. This preserves M6-T2A behavior:
 * projection failure does not invalidate the already-true domain fact.
 *
 * <p>Example:
 *
 * <pre>
 * listeners:
 *   - ExecutionLedgerListener (fails)
 *   - MetricsListener (succeeds)
 *
 * result: MetricsListener still executes
 * </pre>
 *
 * <p><strong>Not a Permanent Platform Contract:</strong>
 *
 * <p>This is the current synchronous projection isolation behavior. It is not a frozen universal
 * "best effort event platform" contract. Future evolution may introduce different failure
 * semantics.
 *
 * @author lov3r
 * @since M6-T2C
 */
final class CompositeExecutionEventListener implements ExecutionEventListener {

  private final List<ExecutionEventListener> listeners;

  /**
   * Create composite listener.
   *
   * @param listeners the listeners to fan out to (defensive copy made)
   */
  CompositeExecutionEventListener(List<ExecutionEventListener> listeners) {
    Objects.requireNonNull(listeners, "listeners cannot be null");
    this.listeners = List.copyOf(listeners); // Defensive immutable copy
  }

  @Override
  public void onEvent(ExecutionEvent event) {
    for (var listener : listeners) {
      try {
        listener.onEvent(event);
      } catch (Exception e) {
        // Listener failure isolated
        // Other listeners continue to receive the event
        // Domain fact remains true regardless of projection failure
        //
        // Future: may add logging here (without introducing SLF4J dependency now)
      }
    }
  }
}
