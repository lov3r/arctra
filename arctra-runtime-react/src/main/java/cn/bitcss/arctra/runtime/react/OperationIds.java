package cn.bitcss.arctra.runtime.react;

import java.util.UUID;

/**
 * Internal utility for generating operation identities.
 *
 * <p>Package-private implementation detail. Operation identity generation is opaque - consumers
 * must not depend on UUID format.
 *
 * <p><strong>Semantic Contract:</strong> operationId is an opaque identity. Implementation may
 * change (e.g., UUID → ULID) without breaking contract.
 *
 * @author lov3r
 * @since M6-T3A
 */
final class OperationIds {

  private OperationIds() {
    throw new UnsupportedOperationException("Utility class");
  }

  /**
   * Generate new operation identity.
   *
   * <p>Returns opaque operation identifier. Format is implementation detail.
   *
   * @return non-null, non-blank operation identity
   */
  static String generate() {
    return UUID.randomUUID().toString();
  }
}
