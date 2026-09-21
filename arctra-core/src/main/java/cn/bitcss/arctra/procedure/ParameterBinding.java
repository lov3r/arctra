package cn.bitcss.arctra.procedure;

import java.util.Objects;

/**
 * Defines how a procedure step parameter is bound at execution time.
 *
 * <p><strong>M8-A V1:</strong> Represents binding structure only. Does NOT implement evaluation
 * logic. Evaluation belongs to future procedure execution track (M8-D).
 *
 * <p>Examples:
 *
 * <pre>
 * INPUT binding:
 *   ParameterBinding(INPUT, "serviceName")
 *   → binds from request input field "serviceName"
 *
 * CONSTANT binding:
 *   ParameterBinding(CONSTANT, "prod")
 *   → binds explicit constant "prod"
 *
 * PREVIOUS_STEP_OUTPUT binding:
 *   ParameterBinding(PREVIOUS_STEP_OUTPUT, "0.serviceId")
 *   → binds from step 0's extracted output "serviceId"
 *
 * RUNTIME_CONTEXT binding:
 *   ParameterBinding(RUNTIME_CONTEXT, "environment")
 *   → binds from runtime context field "environment"
 * </pre>
 *
 * @param source binding source type
 * @param path binding path/reference/value (interpretation depends on source)
 * @author lov3r
 * @since M8-A
 */
public record ParameterBinding(BindingSource source, String path) {

  public ParameterBinding {
    Objects.requireNonNull(source, "source cannot be null");
    if (path == null || path.isBlank()) {
      throw new IllegalArgumentException("path cannot be null or blank");
    }
  }

  /**
   * Validate PREVIOUS_STEP_OUTPUT binding reference.
   *
   * <p>Ensures reference does not point to future steps (forward reference).
   *
   * @param currentStepIndex index of step containing this binding
   * @throws IllegalArgumentException if forward reference detected
   */
  void validatePreviousStepReference(int currentStepIndex) {
    if (source != BindingSource.PREVIOUS_STEP_OUTPUT) {
      return; // Only validate PREVIOUS_STEP_OUTPUT
    }

    // Parse "stepIndex.outputName" format
    int dotIndex = path.indexOf('.');
    if (dotIndex == -1) {
      throw new IllegalArgumentException(
          "PREVIOUS_STEP_OUTPUT path must be 'stepIndex.outputName' format, got: " + path);
    }

    String stepIndexStr = path.substring(0, dotIndex);
    int referencedStepIndex;
    try {
      referencedStepIndex = Integer.parseInt(stepIndexStr);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(
          "PREVIOUS_STEP_OUTPUT step index must be integer, got: " + stepIndexStr);
    }

    if (referencedStepIndex >= currentStepIndex) {
      throw new IllegalArgumentException(
          "PREVIOUS_STEP_OUTPUT cannot reference future step. Current step: "
              + currentStepIndex
              + ", referenced step: "
              + referencedStepIndex);
    }

    if (referencedStepIndex < 0) {
      throw new IllegalArgumentException(
          "PREVIOUS_STEP_OUTPUT step index cannot be negative: " + referencedStepIndex);
    }
  }

  /**
   * Extract referenced step index for PREVIOUS_STEP_OUTPUT binding.
   *
   * @return referenced step index
   * @throws IllegalArgumentException if not PREVIOUS_STEP_OUTPUT or invalid format
   */
  public int extractStepIndex() {
    if (source != BindingSource.PREVIOUS_STEP_OUTPUT) {
      throw new IllegalArgumentException("extractStepIndex only valid for PREVIOUS_STEP_OUTPUT");
    }

    int dotIndex = path.indexOf('.');
    if (dotIndex == -1) {
      throw new IllegalArgumentException("Invalid PREVIOUS_STEP_OUTPUT path format: " + path);
    }

    return Integer.parseInt(path.substring(0, dotIndex));
  }

  /**
   * Extract referenced output name for PREVIOUS_STEP_OUTPUT binding.
   *
   * @return referenced output name
   * @throws IllegalArgumentException if not PREVIOUS_STEP_OUTPUT or invalid format
   */
  public String extractOutputName() {
    if (source != BindingSource.PREVIOUS_STEP_OUTPUT) {
      throw new IllegalArgumentException("extractOutputName only valid for PREVIOUS_STEP_OUTPUT");
    }

    int dotIndex = path.indexOf('.');
    if (dotIndex == -1) {
      throw new IllegalArgumentException("Invalid PREVIOUS_STEP_OUTPUT path format: " + path);
    }

    String outputName = path.substring(dotIndex + 1);
    if (outputName.isBlank()) {
      throw new IllegalArgumentException("Output name cannot be blank in: " + path);
    }

    return outputName;
  }
}
