package cn.bitcss.arctra.procedure;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One step in a linear reusable procedure.
 *
 * <p><strong>M8-A V1:</strong> Linear step with deterministic parameter bindings. No conditions,
 * branches, or dynamic model reasoning.
 *
 * <p>Each step represents one tool operation with:
 *
 * <ul>
 *   <li>Tool identity and compatibility fingerprint
 *   <li>Parameter bindings (INPUT, CONSTANT, PREVIOUS_STEP_OUTPUT, RUNTIME_CONTEXT)
 *   <li>Optional output extractions for future step bindings
 * </ul>
 *
 * <p><strong>Does NOT contain:</strong>
 *
 * <ul>
 *   <li>Runtime identities (operationId, toolCallId, attemptId)
 *   <li>Execution state (current position, checkpoint)
 *   <li>Control flow (nextStep, branch, condition)
 * </ul>
 *
 * @param stepIndex zero-based position in procedure (0, 1, 2, ...)
 * @param toolName logical tool name
 * @param toolFingerprint tool compatibility identity
 * @param parameterBindings parameter name → binding source mapping
 * @param outputExtractions output extractions for future bindings (may be empty)
 * @author lov3r
 * @since M8-A
 */
public record ProcedureStep(
    int stepIndex,
    String toolName,
    ToolCompatibilityFingerprint toolFingerprint,
    Map<String, ParameterBinding> parameterBindings,
    List<OutputExtraction> outputExtractions) {

  public ProcedureStep {
    if (stepIndex < 0) {
      throw new IllegalArgumentException("stepIndex cannot be negative: " + stepIndex);
    }
    if (toolName == null || toolName.isBlank()) {
      throw new IllegalArgumentException("toolName cannot be null or blank");
    }
    Objects.requireNonNull(toolFingerprint, "toolFingerprint cannot be null");
    Objects.requireNonNull(parameterBindings, "parameterBindings cannot be null");
    Objects.requireNonNull(outputExtractions, "outputExtractions cannot be null");

    // Defensive immutable copies
    parameterBindings = Map.copyOf(parameterBindings);
    outputExtractions = List.copyOf(outputExtractions);

    // Validate PREVIOUS_STEP_OUTPUT bindings don't reference future steps
    parameterBindings.values().forEach(binding -> binding.validatePreviousStepReference(stepIndex));

    // Validate output extraction names are unique
    long uniqueOutputNames =
        outputExtractions.stream().map(OutputExtraction::outputName).distinct().count();
    if (uniqueOutputNames != outputExtractions.size()) {
      throw new IllegalArgumentException("Output extraction names must be unique");
    }
  }

  /**
   * Validate that referenced outputs exist in previous steps.
   *
   * <p>Called during procedure construction to ensure PREVIOUS_STEP_OUTPUT bindings reference
   * outputs that actually exist.
   *
   * @param previousSteps steps before this one (for output validation)
   * @throws IllegalArgumentException if binding references non-existent output
   */
  void validateOutputReferences(List<ProcedureStep> previousSteps) {
    for (ParameterBinding binding : parameterBindings.values()) {
      if (binding.source() == BindingSource.PREVIOUS_STEP_OUTPUT) {
        int referencedStepIndex = binding.extractStepIndex();
        String referencedOutputName = binding.extractOutputName();

        if (referencedStepIndex >= previousSteps.size()) {
          throw new IllegalArgumentException(
              "Step "
                  + stepIndex
                  + " references step "
                  + referencedStepIndex
                  + " but only "
                  + previousSteps.size()
                  + " previous steps exist");
        }

        ProcedureStep referencedStep = previousSteps.get(referencedStepIndex);
        boolean outputExists =
            referencedStep.outputExtractions().stream()
                .anyMatch(extraction -> extraction.outputName().equals(referencedOutputName));

        if (!outputExists) {
          throw new IllegalArgumentException(
              "Step "
                  + stepIndex
                  + " references output '"
                  + referencedOutputName
                  + "' from step "
                  + referencedStepIndex
                  + " but that output is not defined");
        }
      }
    }
  }
}
