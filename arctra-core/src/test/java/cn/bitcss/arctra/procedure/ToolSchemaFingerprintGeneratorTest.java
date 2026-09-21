package cn.bitcss.arctra.procedure;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * M8-B 工具模式指纹生成器测试。
 *
 * @author lov3r
 */
class ToolSchemaFingerprintGeneratorTest {

  @Test
  void generateFingerprint() {
    String schema = "{\"type\":\"object\",\"properties\":{\"serviceName\":{\"type\":\"string\"}}}";

    String fingerprint = ToolSchemaFingerprintGenerator.generateFingerprint(schema);

    assertNotNull(fingerprint);
    assertFalse(fingerprint.isBlank());
    // SHA-256 Base64 编码后长度约为 43 字符（无填充）
    assertTrue(fingerprint.length() > 40);
  }

  @Test
  void sameSchemaSameFingerprint() {
    String schema = "{\"type\":\"object\"}";

    String fp1 = ToolSchemaFingerprintGenerator.generateFingerprint(schema);
    String fp2 = ToolSchemaFingerprintGenerator.generateFingerprint(schema);

    assertEquals(fp1, fp2);
  }

  @Test
  void differentSchemaDifferentFingerprint() {
    String schema1 = "{\"type\":\"object\",\"properties\":{\"a\":{}}}";
    String schema2 = "{\"type\":\"object\",\"properties\":{\"b\":{}}}";

    String fp1 = ToolSchemaFingerprintGenerator.generateFingerprint(schema1);
    String fp2 = ToolSchemaFingerprintGenerator.generateFingerprint(schema2);

    assertNotEquals(fp1, fp2);
  }

  @Test
  void rejectNullSchema() {
    assertThrows(
        NullPointerException.class, () -> ToolSchemaFingerprintGenerator.generateFingerprint(null));
  }

  @Test
  void rejectBlankSchema() {
    assertThrows(
        IllegalArgumentException.class,
        () -> ToolSchemaFingerprintGenerator.generateFingerprint("  "));
  }
}
