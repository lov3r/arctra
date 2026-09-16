package cn.bitcss.arctra.runtime.react.durable;

import cn.bitcss.arctra.recovery.InvalidRecoveryResolutionException;
import cn.bitcss.arctra.recovery.OperationResolution;
import cn.bitcss.arctra.recovery.RecoveryResolutionConflictException;
import cn.bitcss.arctra.recovery.ResolutionType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * JDBC-backed invocation-state store with restart-durable persistence.
 *
 * <p>Implements {@link InvocationStateStore} using relational database storage. Provides
 * restart-durable invocation intent recording and recovery resolution required for safe recovery
 * classification after JVM/process restart.
 *
 * <h2>M6-T4E: JDBC Durable Recovery Store Pair</h2>
 *
 * <p>This is the <strong>internal persistent InvocationStateStore implementation</strong> that,
 * when paired with {@link JdbcCheckpointStore} sharing the same DataSource, provides full
 * restart-durable recovery substrate.
 *
 * <h2>M6-T5: Physical Attempt Identity & Recovery Resolution</h2>
 *
 * <p>M6-T5 extends this store to support:
 *
 * <ul>
 *   <li>Physical attempt identity (attemptId)
 *   <li>Multiple attempts per logical operation
 *   <li>Recovery resolution persistence (NOT_EXECUTED, EXECUTED with result)
 *   <li>Attempt enumeration for recovery aggregation
 * </ul>
 *
 * <h2>Idempotent Intent Write</h2>
 *
 * <p>Recording the same (processId, operationId, attemptId) multiple times succeeds. This is
 * monotonic state write (intent exists), NOT claiming.
 *
 * <h2>Strong Read Consistency</h2>
 *
 * <p>Recovery reads MUST observe committed intent/resolution writes. Uses authoritative database
 * path via supplied DataSource.
 *
 * <h2>Schema Requirements</h2>
 *
 * <p>Requires tables:
 *
 * <pre>
 * CREATE TABLE arctra_invocation_intents (
 *   process_id   VARCHAR(255) NOT NULL,
 *   operation_id VARCHAR(255) NOT NULL,
 *   attempt_id   VARCHAR(255) NOT NULL,
 *   recorded_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
 *   PRIMARY KEY (process_id, operation_id, attempt_id)
 * );
 *
 * CREATE TABLE arctra_recovery_resolutions (
 *   process_id       VARCHAR(255) NOT NULL,
 *   operation_id     VARCHAR(255) NOT NULL,
 *   attempt_id       VARCHAR(255) NOT NULL,
 *   resolution_type  VARCHAR(50) NOT NULL,
 *   recovered_result TEXT,
 *   resolved_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
 *   PRIMARY KEY (process_id, operation_id, attempt_id),
 *   FOREIGN KEY (process_id, operation_id, attempt_id)
 *     REFERENCES arctra_invocation_intents(process_id, operation_id, attempt_id)
 * );
 * </pre>
 *
 * <h2>Thread Safety</h2>
 *
 * <p>Thread-safe assuming DataSource is thread-safe.
 *
 * <p>Package-private. Not part of public API.
 *
 * @since M6-T4E
 * @since M6-T5 Attempt identity, recovery resolution
 */
public final class JdbcInvocationStateStore implements InvocationStateStore {

  private final JdbcTemplate jdbcTemplate;

  /**
   * Create JDBC invocation-state store.
   *
   * @param dataSource the JDBC DataSource (must be same as paired JdbcCheckpointStore)
   * @throws NullPointerException if dataSource is null
   */
  public JdbcInvocationStateStore(DataSource dataSource) {
    Objects.requireNonNull(dataSource, "dataSource cannot be null");
    this.jdbcTemplate = new JdbcTemplate(dataSource);
  }

  @Override
  public void recordInvocationIntent(String processId, String operationId, String attemptId) {
    Objects.requireNonNull(processId, "processId cannot be null");
    Objects.requireNonNull(operationId, "operationId cannot be null");
    Objects.requireNonNull(attemptId, "attemptId cannot be null");

    if (processId.isBlank()) {
      throw new IllegalArgumentException("processId cannot be blank");
    }
    if (operationId.isBlank()) {
      throw new IllegalArgumentException("operationId cannot be blank");
    }
    if (attemptId.isBlank()) {
      throw new IllegalArgumentException("attemptId cannot be blank");
    }

    try {
      jdbcTemplate.update(
          """
          INSERT INTO arctra_invocation_intents (process_id, operation_id, attempt_id)
          VALUES (?, ?, ?)
          """,
          processId,
          operationId,
          attemptId);
    } catch (DuplicateKeyException e) {
      // Idempotent success: intent already exists for this attempt
      // This is NOT an error - both workers may proceed
      // At-least-once semantics preserved
    }
  }

  @Override
  public boolean hasInvocationIntent(String processId, String operationId, String attemptId) {
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
    if (attemptId == null) {
      throw new NullPointerException("attemptId cannot be null");
    }
    if (attemptId.isBlank()) {
      throw new IllegalArgumentException("attemptId cannot be blank");
    }

    // Strong authoritative read
    // SQL failure MUST propagate as exception (unknown ≠ absent)
    Integer count =
        jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM arctra_invocation_intents
            WHERE process_id = ? AND operation_id = ? AND attempt_id = ?
            """,
            Integer.class,
            processId,
            operationId,
            attemptId);

    return count != null && count > 0;
  }

  @Override
  public List<InvocationAttempt> findAttempts(String processId, String operationId) {
    Objects.requireNonNull(processId, "processId cannot be null");
    Objects.requireNonNull(operationId, "operationId cannot be null");

    if (processId.isBlank()) {
      throw new IllegalArgumentException("processId cannot be blank");
    }
    if (operationId.isBlank()) {
      throw new IllegalArgumentException("operationId cannot be blank");
    }

    // Query all attempts with left join to resolutions
    return jdbcTemplate.query(
        """
        SELECT
          i.attempt_id,
          i.recorded_at,
          r.resolution_type,
          r.recovered_result,
          r.resolved_at
        FROM arctra_invocation_intents i
        LEFT JOIN arctra_recovery_resolutions r
          ON i.process_id = r.process_id
          AND i.operation_id = r.operation_id
          AND i.attempt_id = r.attempt_id
        WHERE i.process_id = ? AND i.operation_id = ?
        ORDER BY i.recorded_at ASC
        """,
        (rs, rowNum) -> mapInvocationAttempt(rs, operationId),
        processId,
        operationId);
  }

  @Override
  public void recordResolution(
      String processId,
      String operationId,
      String attemptId,
      ResolutionType type,
      String recoveredResult) {

    Objects.requireNonNull(processId, "processId cannot be null");
    Objects.requireNonNull(operationId, "operationId cannot be null");
    Objects.requireNonNull(attemptId, "attemptId cannot be null");
    Objects.requireNonNull(type, "type cannot be null");

    if (processId.isBlank()) {
      throw new IllegalArgumentException("processId cannot be blank");
    }
    if (operationId.isBlank()) {
      throw new IllegalArgumentException("operationId cannot be blank");
    }
    if (attemptId.isBlank()) {
      throw new IllegalArgumentException("attemptId cannot be blank");
    }

    // Validate recoveredResult based on type
    if (type == ResolutionType.EXECUTED && recoveredResult == null) {
      throw new IllegalArgumentException("recoveredResult required for EXECUTED resolution");
    }
    if (type == ResolutionType.NOT_EXECUTED && recoveredResult != null) {
      throw new IllegalArgumentException("recoveredResult must be null for NOT_EXECUTED resolution");
    }

    // Check if intent exists
    if (!hasInvocationIntent(processId, operationId, attemptId)) {
      throw new InvalidRecoveryResolutionException(
          "No invocation intent exists for attempt: " + attemptId);
    }

    // Check for existing resolution
    Optional<OperationResolution> existing = getResolution(processId, operationId, attemptId);
    if (existing.isPresent()) {
      // Semantic equality check for idempotency
      OperationResolution newResolution =
          type == ResolutionType.NOT_EXECUTED
              ? OperationResolution.notExecuted(operationId, attemptId, Instant.now())
              : OperationResolution.executed(operationId, attemptId, recoveredResult, Instant.now());

      if (existing.get().semanticallyEquals(newResolution)) {
        // Idempotent: same semantic resolution
        return;
      } else {
        // Conflict: different resolution
        throw new RecoveryResolutionConflictException(
            String.format(
                "Conflicting resolution for attempt %s: existing=%s, new=%s",
                attemptId, existing.get().type(), type));
      }
    }

    // Insert new resolution
    try {
      jdbcTemplate.update(
          """
          INSERT INTO arctra_recovery_resolutions
            (process_id, operation_id, attempt_id, resolution_type, recovered_result)
          VALUES (?, ?, ?, ?, ?)
          """,
          processId,
          operationId,
          attemptId,
          type.name(),
          recoveredResult);
    } catch (DuplicateKeyException e) {
      // Concurrent resolution race: reread and check semantic equality
      Optional<OperationResolution> reread = getResolution(processId, operationId, attemptId);
      if (reread.isEmpty()) {
        throw new RecoveryResolutionConflictException(
            "Concurrent resolution race but reread found no resolution", e);
      }

      OperationResolution newResolution =
          type == ResolutionType.NOT_EXECUTED
              ? OperationResolution.notExecuted(operationId, attemptId, Instant.now())
              : OperationResolution.executed(operationId, attemptId, recoveredResult, Instant.now());

      if (!reread.get().semanticallyEquals(newResolution)) {
        throw new RecoveryResolutionConflictException(
            String.format(
                "Concurrent conflicting resolution for attempt %s: existing=%s, new=%s",
                attemptId, reread.get().type(), type),
            e);
      }
      // Else: idempotent success after race
    }
  }

  @Override
  public Optional<OperationResolution> getResolution(
      String processId, String operationId, String attemptId) {

    Objects.requireNonNull(processId, "processId cannot be null");
    Objects.requireNonNull(operationId, "operationId cannot be null");
    Objects.requireNonNull(attemptId, "attemptId cannot be null");

    if (processId.isBlank()) {
      throw new IllegalArgumentException("processId cannot be blank");
    }
    if (operationId.isBlank()) {
      throw new IllegalArgumentException("operationId cannot be blank");
    }
    if (attemptId.isBlank()) {
      throw new IllegalArgumentException("attemptId cannot be blank");
    }

    List<OperationResolution> results =
        jdbcTemplate.query(
            """
            SELECT operation_id, attempt_id, resolution_type, recovered_result, resolved_at
            FROM arctra_recovery_resolutions
            WHERE process_id = ? AND operation_id = ? AND attempt_id = ?
            """,
            (rs, rowNum) -> mapOperationResolution(rs),
            processId,
            operationId,
            attemptId);

    return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
  }

  // Row mappers

  private InvocationAttempt mapInvocationAttempt(ResultSet rs, String operationId)
      throws SQLException {
    String attemptId = rs.getString("attempt_id");
    Timestamp recordedAtTs = rs.getTimestamp("recorded_at");
    Instant recordedAt = recordedAtTs.toInstant();

    // Check if resolution exists (LEFT JOIN may have nulls)
    String resolutionTypeStr = rs.getString("resolution_type");
    Optional<OperationResolution> resolution;

    if (resolutionTypeStr == null) {
      resolution = Optional.empty();
    } else {
      ResolutionType type = ResolutionType.valueOf(resolutionTypeStr);
      String recoveredResult = rs.getString("recovered_result");
      Timestamp resolvedAtTs = rs.getTimestamp("resolved_at");
      Instant resolvedAt = resolvedAtTs.toInstant();

      resolution =
          Optional.of(
              type == ResolutionType.NOT_EXECUTED
                  ? OperationResolution.notExecuted(operationId, attemptId, resolvedAt)
                  : OperationResolution.executed(operationId, attemptId, recoveredResult, resolvedAt));
    }

    return new InvocationAttempt(attemptId, recordedAt, resolution);
  }

  private OperationResolution mapOperationResolution(ResultSet rs) throws SQLException {
    String operationId = rs.getString("operation_id");
    String attemptId = rs.getString("attempt_id");
    ResolutionType type = ResolutionType.valueOf(rs.getString("resolution_type"));
    String recoveredResult = rs.getString("recovered_result");
    Timestamp resolvedAtTs = rs.getTimestamp("resolved_at");
    Instant resolvedAt = resolvedAtTs.toInstant();

    return type == ResolutionType.NOT_EXECUTED
        ? OperationResolution.notExecuted(operationId, attemptId, resolvedAt)
        : OperationResolution.executed(operationId, attemptId, recoveredResult, resolvedAt);
  }
}
