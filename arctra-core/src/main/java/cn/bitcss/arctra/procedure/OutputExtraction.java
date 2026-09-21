package cn.bitcss.arctra.procedure;

import java.util.Objects;

/**
 * Defines which output value(s) to extract from a step's tool result for future bindings.
 *
 * <p><strong>M8-A V1:</strong> Simple named output extraction. Represents declarative extraction
 * contract only. Does NOT implement extraction logic.
 *
 * <p>Example: If tool returns {@code {"id": "svc-123", "name": "api", ...}}, extraction with
 * {@code outputName="serviceId"} and {@code jsonPath="/id"} would capture {@code "svc-123"} as
 * {@code serviceId} for future PREVIOUS_STEP_OUTPUT bindings.
 *
 * <p>Extraction implementation belongs to M8-D procedure execution.
 *
 * @param outputName logical name for this extracted output (used in PREVIOUS_STEP_OUTPUT bindings)
 * @param jsonPath simple JSON path for extraction (e.g., "/id", "/metadata/version")
 * @author lov3r
 * @since M8-A
 */
public record OutputExtraction(String outputName, String jsonPath) {

  public OutputExtraction {
    if (outputName == null || outputName.isBlank()) {
      throw new IllegalArgumentException("outputName cannot be null or blank");
    }
    if (jsonPath == null || jsonPath.isBlank()) {
      throw new IllegalArgumentException("jsonPath cannot be null or blank");
    }
  }
}
