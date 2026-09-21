package cn.bitcss.arctra.procedure;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * M8-A tests for ParameterBinding validation.
 *
 * @author lov3r
 */
class ParameterBindingTest {

  @Test
  void createInputBinding() {
    var binding = new ParameterBinding(BindingSource.INPUT, "serviceName");
    assertEquals(BindingSource.INPUT, binding.source());
    assertEquals("serviceName", binding.path());
  }

  @Test
  void createConstantBinding() {
    var binding = new ParameterBinding(BindingSource.CONSTANT, "prod");
    assertEquals(BindingSource.CONSTANT, binding.source());
    assertEquals("prod", binding.path());
  }

  @Test
  void createPreviousStepOutputBinding() {
    var binding = new ParameterBinding(BindingSource.PREVIOUS_STEP_OUTPUT, "0.serviceId");
    assertEquals(BindingSource.PREVIOUS_STEP_OUTPUT, binding.source());
    assertEquals("0.serviceId", binding.path());
  }

  @Test
  void createRuntimeContextBinding() {
    var binding = new ParameterBinding(BindingSource.RUNTIME_CONTEXT, "environment");
    assertEquals(BindingSource.RUNTIME_CONTEXT, binding.source());
    assertEquals("environment", binding.path());
  }

  @Test
  void rejectNullPath() {
    assertThrows(
        IllegalArgumentException.class, () -> new ParameterBinding(BindingSource.INPUT, null));
  }

  @Test
  void rejectBlankPath() {
    assertThrows(
        IllegalArgumentException.class, () -> new ParameterBinding(BindingSource.INPUT, "  "));
  }

  @Test
  void validatePreviousStepOutputNotForwardReference() {
    var binding = new ParameterBinding(BindingSource.PREVIOUS_STEP_OUTPUT, "0.output");

    // Valid: step 1 referencing step 0
    assertDoesNotThrow(() -> binding.validatePreviousStepReference(1));

    // Valid: step 2 referencing step 0
    assertDoesNotThrow(() -> binding.validatePreviousStepReference(2));
  }

  @Test
  void rejectForwardReference() {
    var binding = new ParameterBinding(BindingSource.PREVIOUS_STEP_OUTPUT, "2.output");

    // Invalid: step 1 cannot reference step 2 (future)
    assertThrows(
        IllegalArgumentException.class, () -> binding.validatePreviousStepReference(1));
  }

  @Test
  void rejectSelfReference() {
    var binding = new ParameterBinding(BindingSource.PREVIOUS_STEP_OUTPUT, "1.output");

    // Invalid: step 1 cannot reference itself
    assertThrows(
        IllegalArgumentException.class, () -> binding.validatePreviousStepReference(1));
  }

  @Test
  void rejectNegativeStepIndex() {
    var binding = new ParameterBinding(BindingSource.PREVIOUS_STEP_OUTPUT, "-1.output");

    assertThrows(
        IllegalArgumentException.class, () -> binding.validatePreviousStepReference(0));
  }

  @Test
  void extractStepIndexAndOutputName() {
    var binding = new ParameterBinding(BindingSource.PREVIOUS_STEP_OUTPUT, "2.serviceId");

    assertEquals(2, binding.extractStepIndex());
    assertEquals("serviceId", binding.extractOutputName());
  }

  @Test
  void rejectExtractStepIndexForNonPreviousStepOutput() {
    var binding = new ParameterBinding(BindingSource.INPUT, "serviceName");

    assertThrows(IllegalArgumentException.class, binding::extractStepIndex);
  }

  @Test
  void rejectInvalidPreviousStepOutputFormat() {
    var binding = new ParameterBinding(BindingSource.PREVIOUS_STEP_OUTPUT, "invalidformat");

    assertThrows(
        IllegalArgumentException.class, () -> binding.validatePreviousStepReference(1));
  }

  @Test
  void nonPreviousStepOutputBindingsNotValidated() {
    var inputBinding = new ParameterBinding(BindingSource.INPUT, "anything");
    assertDoesNotThrow(() -> inputBinding.validatePreviousStepReference(0));

    var constantBinding = new ParameterBinding(BindingSource.CONSTANT, "anything");
    assertDoesNotThrow(() -> constantBinding.validatePreviousStepReference(0));
  }
}
