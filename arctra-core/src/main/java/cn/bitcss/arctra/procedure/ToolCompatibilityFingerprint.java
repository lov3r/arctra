package cn.bitcss.arctra.procedure;

import java.util.Objects;

/**
 * Tool compatibility identity for procedure step validation.
 *
 * <p><strong>M8-A V1:</strong> Represents tool compatibility identity using tool name and input
 * schema fingerprint. Procedure execution must validate current tool set against stored
 * fingerprints before execution.
 *
 * <p>Fingerprint generation logic is deferred to M8-B candidate extraction. M8-A defines the
 * representation only.
 *
 * @param toolName logical tool name
 * @param inputSchemaHash deterministic hash of tool input JSON schema (e.g., SHA-256)
 * @author lov3r
 * @since M8-A
 */
public record ToolCompatibilityFingerprint(String toolName, String inputSchemaHash) {

  public ToolCompatibilityFingerprint {
    if (toolName == null || toolName.isBlank()) {
      throw new IllegalArgumentException("toolName cannot be null or blank");
    }
    if (inputSchemaHash == null || inputSchemaHash.isBlank()) {
      throw new IllegalArgumentException("inputSchemaHash cannot be null or blank");
    }
  }
}
