package cn.bitcss.arctra.checkpoint;

import cn.bitcss.arctra.evidence.Evidence;
import java.util.List;

/**
 * Test helper for creating SuspensionCheckpoint instances.
 *
 * <p>M6-T6.4: Reduces test maintenance burden when checkpoint schema evolves.
 *
 * @author lov3r
 * @since M6-T6.4
 */
public class CheckpointTestHelper {

  /**
   * Create waiting checkpoint with minimal required fields.
   *
   * @param processId process identifier
   * @param version checkpoint version
   * @param pendingBatch pending tool calls
   * @return checkpoint with WAITING_FOR_SIGNAL disposition
   */
  public static SuspensionCheckpoint waiting(
      String processId, long version, List<PendingToolCall> pendingBatch) {
    return new SuspensionCheckpoint(
        SuspensionCheckpoint.CURRENT_SCHEMA_VERSION,
        processId,
        version,
        "test-binding-key",
        "test-session",
        ContinuationDisposition.WAITING_FOR_SIGNAL,
        pendingBatch,
        List.of(),
        "test-epoch");
  }

  /**
   * Create runnable checkpoint with minimal required fields.
   *
   * @param processId process identifier
   * @param version checkpoint version
   * @param pendingBatch pending tool calls
   * @return checkpoint with RUNNABLE disposition
   */
  public static SuspensionCheckpoint runnable(
      String processId, long version, List<PendingToolCall> pendingBatch) {
    return new SuspensionCheckpoint(
        SuspensionCheckpoint.CURRENT_SCHEMA_VERSION,
        processId,
        version,
        "test-binding-key",
        "test-session",
        ContinuationDisposition.RUNNABLE,
        pendingBatch,
        List.of(),
        "test-epoch");
  }

  /**
   * Create checkpoint with all fields customizable.
   *
   * @param processId process identifier
   * @param version checkpoint version
   * @param bindingKey runtime binding key
   * @param sessionId session identifier
   * @param disposition continuation disposition
   * @param pendingBatch pending tool calls
   * @param evidences accumulated evidences
   * @param epoch execution epoch
   * @return fully customized checkpoint
   */
  public static SuspensionCheckpoint checkpoint(
      String processId,
      long version,
      String bindingKey,
      String sessionId,
      ContinuationDisposition disposition,
      List<PendingToolCall> pendingBatch,
      List<Evidence> evidences,
      String epoch) {
    return new SuspensionCheckpoint(
        SuspensionCheckpoint.CURRENT_SCHEMA_VERSION,
        processId,
        version,
        bindingKey,
        sessionId,
        disposition,
        pendingBatch,
        evidences,
        epoch);
  }
}
