package cn.bitcss.arctra.runtime.react;

import cn.bitcss.arctra.checkpoint.PendingToolCall;
import cn.bitcss.arctra.checkpoint.SuspensionCheckpoint;
import java.util.List;

/**
 * Test fixture factory for SuspensionCheckpoint.
 *
 * <p>Reduces repetitive checkpoint construction with sensible defaults.
 *
 * <p><strong>Design Principles:</strong>
 *
 * <ul>
 *   <li>Provides common default values (schemaVersion, bindingKey, operationId)
 *   <li>Requires explicit values for test-critical fields (processId, version, pendingBatch)
 *   <li>Does NOT hide semantically important values
 * </ul>
 *
 * <p><strong>M6-T3A:</strong> operationId generation uses deterministic test IDs to avoid
 * test brittleness. Production code uses opaque generated IDs.
 *
 * @author lov3r
 * @since M6-T2.5A-R1
 */
public final class TestCheckpoints {

  private TestCheckpoints() {
    throw new UnsupportedOperationException("Utility class");
  }

  /**
   * Standard binding key used in tests.
   *
   * <p>Tests verifying binding-key-specific behavior should use explicit construction.
   */
  public static final String STANDARD_BINDING_KEY = "test-binding-key";

  /**
   * Standard session ID used in tests.
   *
   * <p>Tests verifying session-specific behavior should use explicit values.
   */
  public static final String STANDARD_SESSION_ID = "test-session";

  /**
   * Generate deterministic test operation ID.
   *
   * <p>Uses simple counter-based IDs for test readability. Production uses opaque UUIDs.
   *
   * @param index operation index
   * @return test operation ID
   * @since M6-T3A
   */
  private static String testOperationId(int index) {
    return "test-op-" + index;
  }

  /**
   * Create suspended checkpoint with version 1 (initial suspension).
   *
   * <p>Uses:
   *
   * <ul>
   *   <li>Current schema version
   *   <li>Version 1 (initial)
   *   <li>Standard binding key
   *   <li>Standard session ID
   *   <li>Empty accumulated evidences
   *   <li>Current execution epoch (M6-T4F)
   * </ul>
   *
   * @param processId process identifier (explicit - test-critical)
   * @param pendingBatch pending tool calls (explicit - test-critical)
   * @return suspended checkpoint at version 1
   */
  public static SuspensionCheckpoint suspended(
      String processId, List<PendingToolCall> pendingBatch) {
    return suspended(processId, 1L, pendingBatch);
  }

  /**
   * Create suspended checkpoint with explicit version.
   *
   * <p>Uses:
   *
   * <ul>
   *   <li>Current schema version
   *   <li>Standard binding key
   *   <li>Standard session ID
   *   <li>Empty accumulated evidences
   *   <li>Current execution epoch (M6-T4F)
   * </ul>
   *
   * <p>Use this when testing version-specific behavior (e.g., CHECK A stale version, re-suspension
   * with version increment).
   *
   * @param processId process identifier (explicit - test-critical)
   * @param version checkpoint version (explicit - test-critical for versioning tests)
   * @param pendingBatch pending tool calls (explicit - test-critical)
   * @return suspended checkpoint at specified version
   */
  public static SuspensionCheckpoint suspended(
      String processId, long version, List<PendingToolCall> pendingBatch) {
    return new SuspensionCheckpoint(
        SuspensionCheckpoint.CURRENT_SCHEMA_VERSION,
        processId,
        version,
        STANDARD_BINDING_KEY,
        STANDARD_SESSION_ID,
        pendingBatch,
        List.of(),
        ExecutionIncarnation.current()); // M6-T4F: current epoch
  }

  /**
   * Create suspended checkpoint with custom session ID.
   *
   * <p>Use when testing session-specific behavior (e.g., ChatMemory isolation, multi-session
   * scenarios).
   *
   * @param processId process identifier
   * @param version checkpoint version
   * @param sessionId custom session ID (explicit - test-critical for session tests)
   * @param pendingBatch pending tool calls
   * @return suspended checkpoint with custom session
   */
  public static SuspensionCheckpoint suspendedWithSession(
      String processId, long version, String sessionId, List<PendingToolCall> pendingBatch) {
    return new SuspensionCheckpoint(
        SuspensionCheckpoint.CURRENT_SCHEMA_VERSION,
        processId,
        version,
        STANDARD_BINDING_KEY,
        sessionId,
        pendingBatch,
        List.of(),
        ExecutionIncarnation.current()); // M6-T4F
  }

  /**
   * Create suspended checkpoint with custom binding key.
   *
   * <p>Use when testing binding-key-specific behavior (e.g., binding resolution, multi-tenant
   * scenarios).
   *
   * @param processId process identifier
   * @param version checkpoint version
   * @param bindingKey custom binding key (explicit - test-critical for binding tests)
   * @param sessionId session ID
   * @param pendingBatch pending tool calls
   * @return suspended checkpoint with custom binding key
   */
  public static SuspensionCheckpoint suspendedWithBindingKey(
      String processId,
      long version,
      String bindingKey,
      String sessionId,
      List<PendingToolCall> pendingBatch) {
    return new SuspensionCheckpoint(
        SuspensionCheckpoint.CURRENT_SCHEMA_VERSION,
        processId,
        version,
        bindingKey,
        sessionId,
        pendingBatch,
        List.of(),
        ExecutionIncarnation.current()); // M6-T4F
  }

  /**
   * Create suspended checkpoint with explicit version (no pending batch).
   *
   * <p>Use for testing checkpoint loading and version validation when pending batch content is not
   * relevant.
   *
   * @param processId process identifier
   * @param version checkpoint version
   * @return suspended checkpoint with dummy pending batch
   */
  public static SuspensionCheckpoint withVersion(String processId, long version) {
    return suspended(
        processId, version, List.of(new PendingToolCall(testOperationId(0), "tc-dummy", "dummyTool", "{}")));
  }

  /**
   * Create suspended checkpoint with custom binding key and session.
   *
   * <p>Use for testing binding resolution with checkpoint identity.
   *
   * @param processId process identifier
   * @param version checkpoint version
   * @param bindingKey custom binding key
   * @param sessionId session ID
   * @return suspended checkpoint with custom binding key
   */
  public static SuspensionCheckpoint withBindingKey(
      String processId, long version, String bindingKey, String sessionId) {
    return suspendedWithBindingKey(
        processId,
        version,
        bindingKey,
        sessionId,
        List.of(new PendingToolCall(testOperationId(0), "tc-dummy", "dummyTool", "{}")));
  }

  /**
   * Create suspended checkpoint with binding key and evidences.
   *
   * <p>Use for testing evidence accumulation across checkpoint versions.
   *
   * @param processId process identifier
   * @param version checkpoint version
   * @param bindingKey binding key
   * @param sessionId session ID
   * @param evidences accumulated evidences
   * @return suspended checkpoint with evidences
   */
  public static SuspensionCheckpoint withBindingKeyAndEvidences(
      String processId,
      long version,
      String bindingKey,
      String sessionId,
      java.util.List<cn.bitcss.arctra.evidence.Evidence> evidences) {
    return new SuspensionCheckpoint(
        SuspensionCheckpoint.CURRENT_SCHEMA_VERSION,
        processId,
        version,
        bindingKey,
        sessionId,
        List.of(new PendingToolCall(testOperationId(0), "tc-dummy", "dummyTool", "{}")),
        evidences,
        ExecutionIncarnation.current()); // M6-T4F
  }

  /**
   * Create checkpoint with custom execution epoch (M6-T4F test helper).
   *
   * <p>Use for testing cross-incarnation recovery mode selection.
   *
   * @param processId process identifier
   * @param version checkpoint version
   * @param executionEpoch custom execution epoch
   * @return checkpoint with custom epoch
   * @since M6-T4F
   */
  public static SuspensionCheckpoint withEpoch(
      String processId, long version, String executionEpoch) {
    return new SuspensionCheckpoint(
        SuspensionCheckpoint.CURRENT_SCHEMA_VERSION,
        processId,
        version,
        STANDARD_BINDING_KEY,
        STANDARD_SESSION_ID,
        List.of(new PendingToolCall(testOperationId(0), "tc-dummy", "dummyTool", "{}")),
        List.of(),
        executionEpoch);
  }
}
