package cn.bitcss.arctra.procedure;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Objects;

/**
 * 工具模式指纹生成器。
 *
 * <p><strong>M8-B：</strong>生成确定性工具输入模式指纹，用于过程兼容性验证。
 *
 * <p>当工具的输入 JSON Schema 语义改变时，指纹应该改变。
 *
 * @author lov3r
 * @since M8-B
 */
class ToolSchemaFingerprintGenerator {

  /**
   * 生成工具输入模式指纹。
   *
   * <p>使用 SHA-256 哈希输入 schema JSON 表示。
   *
   * <p><strong>V1 简化：</strong>直接哈希 schema JSON 字符串。未来可能需要规范化以处理等价但格式不同的
   * schema。
   *
   * @param inputSchemaJson 输入模式 JSON 字符串（应该是规范化的）
   * @return Base64 编码的 SHA-256 哈希
   * @throws IllegalArgumentException 如果 schema 为 null/blank
   */
  static String generateFingerprint(String inputSchemaJson) {
    Objects.requireNonNull(inputSchemaJson, "inputSchemaJson cannot be null");
    if (inputSchemaJson.isBlank()) {
      throw new IllegalArgumentException("inputSchemaJson cannot be blank");
    }

    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(inputSchemaJson.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
    } catch (NoSuchAlgorithmException e) {
      // SHA-256 是 Java 标准算法，不应该失败
      throw new IllegalStateException("SHA-256 algorithm not available", e);
    }
  }
}
