package cn.bitcss.arctra.procedure;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * M8-B 绑定捕获验证器测试。
 *
 * @author lov3r
 */
class BindingCaptureValidatorTest {

  @Test
  void safeConstantAllowed() {
    var result = BindingCaptureValidator.evaluateCapturability("environment", "prod", true);
    assertEquals(BindingCapturability.CONSTANT_ALLOWED, result);
  }

  @Test
  void unknownConstantNotCaptured() {
    var result = BindingCaptureValidator.evaluateCapturability("region", "us-west-2", true);
    assertEquals(BindingCapturability.NEVER_CAPTURE, result);
  }

  @Test
  void referenceIsSafeToCapture() {
    var result = BindingCaptureValidator.evaluateCapturability("serviceName", "serviceName", false);
    assertEquals(BindingCapturability.SAFE_TO_CAPTURE, result);
  }

  @Test
  void secretParamNameNeverCaptured() {
    var result = BindingCaptureValidator.evaluateCapturability("password", "prod", true);
    assertEquals(BindingCapturability.NEVER_CAPTURE, result);

    result = BindingCaptureValidator.evaluateCapturability("apiKey", "key123", true);
    assertEquals(BindingCapturability.NEVER_CAPTURE, result);

    result = BindingCaptureValidator.evaluateCapturability("token", "xyz", true);
    assertEquals(BindingCapturability.NEVER_CAPTURE, result);
  }

  @Test
  void looksLikeReferenceDetectsPreviousStepOutput() {
    assertTrue(BindingCaptureValidator.looksLikeReference("0.serviceId"));
    assertTrue(BindingCaptureValidator.looksLikeReference("2.result"));
  }

  @Test
  void looksLikeReferenceDetectsSimpleIdentifier() {
    assertTrue(BindingCaptureValidator.looksLikeReference("serviceName"));
    assertTrue(BindingCaptureValidator.looksLikeReference("environment"));
  }

  @Test
  void looksLikeReferenceRejectsLiterals() {
    assertFalse(BindingCaptureValidator.looksLikeReference("us-west-2"));
    assertFalse(BindingCaptureValidator.looksLikeReference("hello world"));
    assertFalse(BindingCaptureValidator.looksLikeReference(""));
  }
}
