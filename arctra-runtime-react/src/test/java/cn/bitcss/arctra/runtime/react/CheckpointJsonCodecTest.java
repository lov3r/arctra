package cn.bitcss.arctra.runtime.react;

import static org.assertj.core.api.Assertions.*;

import cn.bitcss.arctra.checkpoint.PendingToolCall;
import static cn.bitcss.arctra.checkpoint.CheckpointTestHelper.*;
import cn.bitcss.arctra.checkpoint.ContinuationDisposition;
import cn.bitcss.arctra.checkpoint.SuspensionCheckpoint;
import cn.bitcss.arctra.evidence.Evidence;
import cn.bitcss.arctra.runtime.react.persistence.CheckpointJsonCodec;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CheckpointJsonCodec}.
 *
 * <p>Proves JSON serialization preserves all recovery-critical fields.
 *
 * @author lov3r
 * @since M6-T4E
 */
@DisplayName("CheckpointJsonCodec")
class CheckpointJsonCodecTest {

  private final CheckpointJsonCodec codec = new CheckpointJsonCodec();

  @Test
  @DisplayName("Round-trip with complete checkpoint")
  void roundTripComplete() {
    SuspensionCheckpoint original =
        new SuspensionCheckpoint(
            "1.0",
            "proc-123",
            5L,
            "incident-agent",
            "session-abc",
            ContinuationDisposition.WAITING_FOR_SIGNAL,
            List.of(
                new PendingToolCall("op-1", "tc-1", "query_logs", "{\"severity\":\"error\"}"),
                new PendingToolCall("op-2", "tc-2", "get_deployment", "{\"service\":\"api\"}")),
            List.of(
                new Evidence("query_logs", "Found 42 errors"),
                new Evidence("get_deployment", "Deployment v1.2.3")),
            "test-epoch");

    String json = codec.serialize(original);
    SuspensionCheckpoint deserialized = codec.deserialize(json);

    assertThat(deserialized).isEqualTo(original);
  }

  @Test
  @DisplayName("Preserve nullable sessionId")
  void nullableSessionId() {
    SuspensionCheckpoint withNull =
        new SuspensionCheckpoint(
            "1.0",
            "proc-456",
            1L,
            "test-key",
            null, // nullable sessionId
            ContinuationDisposition.WAITING_FOR_SIGNAL,
            List.of(new PendingToolCall("op-x", "tc-x", "tool", "{}")),
            List.of(), "test-epoch");

    String json = codec.serialize(withNull);
    SuspensionCheckpoint deserialized = codec.deserialize(json);

    assertThat(deserialized.sessionId()).isNull();
    assertThat(deserialized).isEqualTo(withNull);
  }

  @Test
  @DisplayName("Preserve operationId exactly")
  void operationIdPreserved() {
    String operationId = "op-uuid-12345-abcde";

    SuspensionCheckpoint checkpoint =
        new SuspensionCheckpoint(
            "1.0",
            "proc-1",
            1L,
            "key",
            "session",
            ContinuationDisposition.WAITING_FOR_SIGNAL,
            List.of(new PendingToolCall(operationId, "tc-1", "tool", "{}")),
            List.of(), "test-epoch");

    String json = codec.serialize(checkpoint);
    SuspensionCheckpoint deserialized = codec.deserialize(json);

    assertThat(deserialized.pendingBatch().get(0).operationId()).isEqualTo(operationId);
  }

  @Test
  @DisplayName("Preserve toolCallId exactly")
  void toolCallIdPreserved() {
    String toolCallId = "call_abc123XYZ";

    SuspensionCheckpoint checkpoint =
        new SuspensionCheckpoint(
            "1.0",
            "proc-1",
            1L,
            "key",
            "session",
            ContinuationDisposition.WAITING_FOR_SIGNAL,
            List.of(new PendingToolCall("op-1", toolCallId, "tool", "{}")),
            List.of(), "test-epoch");

    String json = codec.serialize(checkpoint);
    SuspensionCheckpoint deserialized = codec.deserialize(json);

    assertThat(deserialized.pendingBatch().get(0).toolCallId()).isEqualTo(toolCallId);
  }

  @Test
  @DisplayName("Preserve tool arguments exactly")
  void argumentsPreserved() {
    String arguments = "{\"query\":\"error\",\"limit\":100}";

    SuspensionCheckpoint checkpoint =
        new SuspensionCheckpoint(
            "1.0",
            "proc-1",
            1L,
            "key",
            "session",
            ContinuationDisposition.WAITING_FOR_SIGNAL,
            List.of(new PendingToolCall("op-1", "tc-1", "query", arguments)),
            List.of(), "test-epoch");

    String json = codec.serialize(checkpoint);
    SuspensionCheckpoint deserialized = codec.deserialize(json);

    assertThat(deserialized.pendingBatch().get(0).arguments()).isEqualTo(arguments);
  }

  @Test
  @DisplayName("Preserve Evidence content exactly")
  void evidencePreserved() {
    Evidence evidence = new Evidence("tool-x", "Complex\nMulti-line\nResult");

    SuspensionCheckpoint checkpoint =
        new SuspensionCheckpoint(
            "1.0",
            "proc-1",
            1L,
            "key",
            "session",
            ContinuationDisposition.WAITING_FOR_SIGNAL,
            List.of(new PendingToolCall("op-1", "tc-1", "tool", "{}")),
            List.of(evidence),
            "test-epoch");

    String json = codec.serialize(checkpoint);
    SuspensionCheckpoint deserialized = codec.deserialize(json);

    assertThat(deserialized.accumulatedEvidences()).containsExactly(evidence);
  }

  @Test
  @DisplayName("Preserve multiple pending operations in order")
  void multiplePendingOperationsOrdered() {
    List<PendingToolCall> pending =
        List.of(
            new PendingToolCall("op-1", "tc-1", "tool-a", "{}"),
            new PendingToolCall("op-2", "tc-2", "tool-b", "{}"),
            new PendingToolCall("op-3", "tc-3", "tool-c", "{}"));

    SuspensionCheckpoint checkpoint =
        new SuspensionCheckpoint("1.0", "proc-1", 1L, "key", "session", ContinuationDisposition.WAITING_FOR_SIGNAL, pending, List.of(), "test-epoch");

    String json = codec.serialize(checkpoint);
    SuspensionCheckpoint deserialized = codec.deserialize(json);

    assertThat(deserialized.pendingBatch()).containsExactlyElementsOf(pending);
  }

  @Test
  @DisplayName("Empty accumulated evidences round-trip")
  void emptyEvidences() {
    SuspensionCheckpoint checkpoint =
        new SuspensionCheckpoint(
            "1.0",
            "proc-1",
            1L,
            "key",
            "session",
            ContinuationDisposition.WAITING_FOR_SIGNAL,
            List.of(new PendingToolCall("op-1", "tc-1", "tool", "{}")),
            List.of(),
            "test-epoch"); // empty evidences

    String json = codec.serialize(checkpoint);
    SuspensionCheckpoint deserialized = codec.deserialize(json);

    assertThat(deserialized.accumulatedEvidences()).isEmpty();
  }

  @Test
  @DisplayName("Reject JSON missing operationId")
  void rejectMissingOperationId() {
    String invalidJson =
        """
        {
          "schemaVersion": "1.0",
          "processId": "proc-1",
          "checkpointVersion": 1,
          "runtimeBindingKey": "key",
          "sessionId": "session",
          "pendingBatch": [
            {
              "toolCallId": "tc-1",
              "toolName": "tool",
              "arguments": "{}"
            }
          ],
          "accumulatedEvidences": []
        }
        """;

    assertThatThrownBy(() -> codec.deserialize(invalidJson))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("operationId");
  }

  @Test
  @DisplayName("Reject JSON missing checkpointVersion")
  void rejectMissingCheckpointVersion() {
    String invalidJson =
        """
        {
          "schemaVersion": "1.0",
          "processId": "proc-1",
          "runtimeBindingKey": "key",
          "sessionId": "session",
          "pendingBatch": [
            {
              "operationId": "op-1",
              "toolCallId": "tc-1",
              "toolName": "tool",
              "arguments": "{}"
            }
          ],
          "accumulatedEvidences": []
        }
        """;

    assertThatThrownBy(() -> codec.deserialize(invalidJson))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("checkpointVersion");
  }
}
