package cn.bitcss.arctra.runtime.react;

import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * JDBC-backed invocation-state store with restart-durable persistence.
 *
 * <p>Implements {@link InvocationStateStore} using relational database storage. Provides
 * restart-durable invocation intent recording required for safe recovery classification after
 * JVM/process restart.
 *
 * <h2>M6-T4E: JDBC Durable Recovery Store Pair</h2>
 *
 * <p>This is the <strong>internal persistent InvocationStateStore implementation</strong> that,
 * when paired with {@link JdbcCheckpointStore} sharing the same DataSource, provides full
 * restart-durable recovery substrate.
 *
 * <h2>Idempotent Intent Write</h2>
 *
 * <p>Recording the same (processId, operationId) multiple times succeeds. This is monotonic state
 * write (intent exists), NOT claiming. Multiple workers may record intent and execute.
 *
 * <h2>Strong Read Consistency</h2>
 *
 * <p>Recovery reads MUST observe committed intent writes (no stale false). Uses authoritative
 * database path via supplied DataSource.
 *
 * <h2>Schema Requirements</h2>
 *
 * <p>Requires table:
 *
 * <pre>
 * CREATE TABLE arctra_invocation_intents (
 *   process_id   VARCHAR(255) NOT NULL,
 *   operation_id VARCHAR(255) NOT NULL,
 *   recorded_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
 *   PRIMARY KEY (process_id, operation_id)
 * );
 * </pre>
 *
 * <p>Schema initialization is application/deployment responsibility.
 *
 * <h2>Thread Safety</h2>
 *
 * <p>Thread-safe assuming DataSource is thread-safe.
 *
 * <p>Package-private. Not part of public API.
 *
 * @author lov3r
 * @since M6-T4E
 */
final class JdbcInvocationStateStore implements InvocationStateStore {

  private final JdbcTemplate jdbcTemplate;

  /**
   * Create JDBC invocation-state store.
   *
   * @param dataSource the JDBC DataSource (must be same as paired JdbcCheckpointStore)
   * @throws NullPointerException if dataSource is null
   */
  JdbcInvocationStateStore(DataSource dataSource) {
    Objects.requireNonNull(dataSource, "dataSource cannot be null");
    this.jdbcTemplate = new JdbcTemplate(dataSource);
  }

  @Override
  public void recordInvocationIntent(String processId, String operationId) {
    Objects.requireNonNull(processId, "processId cannot be null");
    Objects.requireNonNull(operationId, "operationId cannot be null");

    if (processId.isBlank()) {
      throw new IllegalArgumentException("processId cannot be blank");
    }
    if (operationId.isBlank()) {
      throw new IllegalArgumentException("operationId cannot be blank");
    }

    try {
      jdbcTemplate.update(
          """
          INSERT INTO arctra_invocation_intents (process_id, operation_id)
          VALUES (?, ?)
          """,
          processId,
          operationId);
    } catch (DuplicateKeyException e) {
      // Idempotent success: intent already exists
      // This is NOT an error - both workers may proceed
      // At-least-once semantics preserved
    }
  }

  @Override
  public boolean hasInvocationIntent(String processId, String operationId) {
    // Validate inputs (match recordInvocationIntent validation)
    if (processId == null) {
      throw new NullPointerException("processId cannot be null");
    }
    if (processId.isBlank()) {
      throw new IllegalArgumentException("processId cannot be blank");
    }
    if (operationId == null) {
      throw new NullPointerException("operationId cannot be null");
    }
    if (operationId.isBlank()) {
      throw new IllegalArgumentException("operationId cannot be blank");
    }

    // Strong authoritative read
    // SQL failure MUST propagate as exception (unknown ≠ absent)
    Integer count =
        jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM arctra_invocation_intents
            WHERE process_id = ? AND operation_id = ?
            """,
            Integer.class,
            processId,
            operationId);

    return count != null && count > 0;
  }
}
