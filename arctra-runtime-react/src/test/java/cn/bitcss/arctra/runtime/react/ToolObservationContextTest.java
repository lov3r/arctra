package cn.bitcss.arctra.runtime.react;

import static org.assertj.core.api.Assertions.*;

import cn.bitcss.arctra.execution.ExecutionEventListener;
import cn.bitcss.arctra.runtime.react.tool.ToolObservationContext;
import org.junit.jupiter.api.Test;

/**
 * Tests for ToolObservationContext.
 *
 * <p>Verifies invariant enforcement for tool execution observation correlation context.
 *
 * @author lov3r
 * @since M6-T2.5A-R2.1
 */
class ToolObservationContextTest {

  private static final String VALID_PROCESS_ID = "process-123";
  private static final long VALID_VERSION = 1L;
  private static final ExecutionEventListener NOOP_LISTENER = event -> {};

  /**
   * Test 1: Valid construction succeeds and retains exact values.
   */
  @Test
  void validConstruction_succeeds() {
    // Given: valid parameters
    String processId = VALID_PROCESS_ID;
    long checkpointVersion = VALID_VERSION;
    ExecutionEventListener listener = NOOP_LISTENER;

    // When: construct context
    ToolObservationContext context =
        new ToolObservationContext(processId, checkpointVersion, "test-op-1", listener);

    // Then: exact values retained
    assertThat(context.processId()).isEqualTo(processId);
    assertThat(context.checkpointVersion()).isEqualTo(checkpointVersion);
    assertThat(context.eventListener()).isSameAs(listener);
  }

  /**
   * Test 2: Null processId rejected.
   */
  @Test
  void nullProcessId_throwsNullPointerException() {
    assertThatThrownBy(() -> new ToolObservationContext(null, VALID_VERSION, "op-1", NOOP_LISTENER))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("processId cannot be null");
  }

  /**
   * Test 3: Blank processId rejected (empty string).
   */
  @Test
  void blankProcessId_empty_throwsIllegalArgumentException() {
    assertThatThrownBy(() -> new ToolObservationContext("", VALID_VERSION, "op-1", NOOP_LISTENER))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("processId cannot be blank");
  }

  /**
   * Test 3b: Blank processId rejected (whitespace-only).
   */
  @Test
  void blankProcessId_whitespaceOnly_throwsIllegalArgumentException() {
    assertThatThrownBy(() -> new ToolObservationContext("   ", VALID_VERSION, "op-1", NOOP_LISTENER))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("processId cannot be blank");
  }

  /**
   * Test 4: checkpointVersion == 0 rejected.
   */
  @Test
  void checkpointVersionZero_throwsIllegalArgumentException() {
    assertThatThrownBy(() -> new ToolObservationContext(VALID_PROCESS_ID, 0L, "op-1", NOOP_LISTENER))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("checkpointVersion must be positive");
  }

  /**
   * Test 5: checkpointVersion < 0 rejected.
   */
  @Test
  void checkpointVersionNegative_throwsIllegalArgumentException() {
    assertThatThrownBy(() -> new ToolObservationContext(VALID_PROCESS_ID, -1L, "op-1", NOOP_LISTENER))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("checkpointVersion must be positive");
  }

  /**
   * Test 6: Null eventListener rejected.
   */
  @Test
  void nullEventListener_throwsNullPointerException() {
    assertThatThrownBy(() -> new ToolObservationContext(VALID_PROCESS_ID, VALID_VERSION, "op-1", null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("eventListener cannot be null");
  }
}
