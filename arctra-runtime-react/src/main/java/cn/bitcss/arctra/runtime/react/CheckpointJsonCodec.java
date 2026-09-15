package cn.bitcss.arctra.runtime.react;

import cn.bitcss.arctra.checkpoint.PendingToolCall;
import cn.bitcss.arctra.checkpoint.SuspensionCheckpoint;
import cn.bitcss.arctra.evidence.Evidence;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * JSON serialization codec for {@link SuspensionCheckpoint}.
 *
 * <p>Handles conversion between SuspensionCheckpoint domain objects and JSON storage
 * representation. This is an internal storage concern, NOT public API.
 *
 * <h2>M6-T4E: Checkpoint Serialization</h2>
 *
 * <p>Critical requirements:
 *
 * <ul>
 *   <li>Preserve operationId exactly (no regeneration)
 *   <li>Preserve toolCallId exactly (provider protocol correlation)
 *   <li>Preserve nullable sessionId
 *   <li>Preserve Evidence content exactly
 *   <li>Round-trip must be lossless for recovery-critical fields
 * </ul>
 *
 * <h2>Thread Safety</h2>
 *
 * <p>Thread-safe (ObjectMapper is thread-safe after configuration).
 *
 * <p>Package-private. Not part of public API.
 *
 * @author lov3r
 * @since M6-T4E
 */
final class CheckpointJsonCodec {

  private final ObjectMapper objectMapper;

  CheckpointJsonCodec() {
    this.objectMapper = new ObjectMapper();
  }

  /**
   * Serialize checkpoint to JSON.
   *
   * @param checkpoint the checkpoint to serialize
   * @return JSON string representation
   * @throws IllegalStateException if serialization fails
   */
  String serialize(SuspensionCheckpoint checkpoint) {
    try {
      ObjectNode root = objectMapper.createObjectNode();

      root.put("schemaVersion", checkpoint.schemaVersion());
      root.put("processId", checkpoint.processId());
      root.put("checkpointVersion", checkpoint.checkpointVersion());
      root.put("runtimeBindingKey", checkpoint.runtimeBindingKey());

      // sessionId may be null
      if (checkpoint.sessionId() != null) {
        root.put("sessionId", checkpoint.sessionId());
      } else {
        root.putNull("sessionId");
      }

      // Pending batch
      ArrayNode pendingArray = root.putArray("pendingBatch");
      for (PendingToolCall pending : checkpoint.pendingBatch()) {
        ObjectNode pendingNode = pendingArray.addObject();
        pendingNode.put("operationId", pending.operationId());
        pendingNode.put("toolCallId", pending.toolCallId());
        pendingNode.put("toolName", pending.toolName());
        pendingNode.put("arguments", pending.arguments());
      }

      // Accumulated evidences
      ArrayNode evidencesArray = root.putArray("accumulatedEvidences");
      for (Evidence evidence : checkpoint.accumulatedEvidences()) {
        ObjectNode evidenceNode = evidencesArray.addObject();
        evidenceNode.put("source", evidence.source());
        evidenceNode.put("content", evidence.content());
      }

      return objectMapper.writeValueAsString(root);

    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Failed to serialize checkpoint: " + e.getMessage(), e);
    }
  }

  /**
   * Deserialize checkpoint from JSON.
   *
   * @param json the JSON string
   * @return deserialized checkpoint
   * @throws IllegalStateException if deserialization fails
   * @throws IllegalArgumentException if JSON is missing required fields
   */
  SuspensionCheckpoint deserialize(String json) {
    try {
      JsonNode root = objectMapper.readTree(json);

      String schemaVersion = requireText(root, "schemaVersion");
      String processId = requireText(root, "processId");
      long checkpointVersion = requireLong(root, "checkpointVersion");
      String runtimeBindingKey = requireText(root, "runtimeBindingKey");
      String sessionId = root.path("sessionId").isNull() ? null : root.path("sessionId").asText();

      // Pending batch
      List<PendingToolCall> pendingBatch = new ArrayList<>();
      JsonNode pendingArray = root.path("pendingBatch");
      if (!pendingArray.isArray()) {
        throw new IllegalArgumentException("pendingBatch must be an array");
      }
      for (JsonNode pendingNode : pendingArray) {
        String operationId = requireText(pendingNode, "operationId");
        String toolCallId = requireText(pendingNode, "toolCallId");
        String toolName = requireText(pendingNode, "toolName");
        String arguments = requireText(pendingNode, "arguments");
        pendingBatch.add(new PendingToolCall(operationId, toolCallId, toolName, arguments));
      }

      // Accumulated evidences
      List<Evidence> accumulatedEvidences = new ArrayList<>();
      JsonNode evidencesArray = root.path("accumulatedEvidences");
      if (!evidencesArray.isArray()) {
        throw new IllegalArgumentException("accumulatedEvidences must be an array");
      }
      for (JsonNode evidenceNode : evidencesArray) {
        String source = requireText(evidenceNode, "source");
        String content = requireText(evidenceNode, "content");
        accumulatedEvidences.add(new Evidence(source, content));
      }

      return new SuspensionCheckpoint(
          schemaVersion,
          processId,
          checkpointVersion,
          runtimeBindingKey,
          sessionId,
          pendingBatch,
          accumulatedEvidences);

    } catch (IOException e) {
      throw new IllegalStateException("Failed to deserialize checkpoint: " + e.getMessage(), e);
    }
  }

  private String requireText(JsonNode node, String fieldName) {
    JsonNode field = node.path(fieldName);
    if (field.isMissingNode() || field.isNull() || !field.isTextual()) {
      throw new IllegalArgumentException(
          "Required field '" + fieldName + "' is missing or not a text value");
    }
    return field.asText();
  }

  private long requireLong(JsonNode node, String fieldName) {
    JsonNode field = node.path(fieldName);
    if (field.isMissingNode() || !field.isNumber()) {
      throw new IllegalArgumentException(
          "Required field '" + fieldName + "' is missing or not a number");
    }
    return field.asLong();
  }
}
