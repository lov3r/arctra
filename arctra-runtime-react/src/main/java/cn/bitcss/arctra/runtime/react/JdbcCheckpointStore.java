package cn.bitcss.arctra.runtime.react;

import cn.bitcss.arctra.checkpoint.CheckpointAlreadyExistsException;
import cn.bitcss.arctra.checkpoint.CheckpointStore;
import cn.bitcss.arctra.checkpoint.SuspensionCheckpoint;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * JDBC-backed checkpoint store with restart-durable persistence.
 *
 * <p>Implements {@link CheckpointStore} using relational database storage for JVM-restart-durable
 * recovery. Provides atomic CAS operations (replaceIfVersion, deleteIfVersion) suitable for
 * multi-node deployment.
 *
 * <h2>M6-T4E: JDBC Durable Recovery Store Pair</h2>
 *
 * <p>This is the <strong>official persistent CheckpointStore implementation</strong> that, when
 * paired with {@link JdbcInvocationStateStore} sharing the same DataSource, provides full
 * restart-durable recovery substrate.
 *
 * <h2>DataSource Ownership</h2>
 *
 * <p>Application owns DataSource configuration (connection pool, URL, credentials, transaction
 * manager). This store does NOT manage DataSource lifecycle.
 *
 * <h2>Schema Requirements</h2>
 *
 * <p>Requires table:
 *
 * <pre>
 * CREATE TABLE arctra_checkpoints (
 *   process_id          VARCHAR(255) PRIMARY KEY,
 *   checkpoint_version  BIGINT NOT NULL,
 *   schema_version      VARCHAR(32) NOT NULL,
 *   runtime_binding_key VARCHAR(255) NOT NULL,
 *   session_id          VARCHAR(255),
 *   checkpoint_data     TEXT NOT NULL
 * );
 * </pre>
 *
 * <p>Schema initialization is application/deployment responsibility.
 *
 * <h2>Consistency Guarantees</h2>
 *
 * <ul>
 *   <li>Strong read-after-write (via committed transaction)
 *   <li>Atomic CAS (conditional UPDATE/DELETE with version predicate)
 *   <li>Cross-instance visibility after commit
 *   <li>No async replica reads
 * </ul>
 *
 * <h2>Thread Safety</h2>
 *
 * <p>Thread-safe assuming DataSource is thread-safe. No per-call shared mutable state.
 *
 * @author lov3r
 * @since M6-T4E
 */
public final class JdbcCheckpointStore implements CheckpointStore {

  private final JdbcTemplate jdbcTemplate;
  private final CheckpointJsonCodec codec;

  /**
   * Create JDBC checkpoint store.
   *
   * @param dataSource the JDBC DataSource (application-configured)
   * @throws NullPointerException if dataSource is null
   */
  public JdbcCheckpointStore(DataSource dataSource) {
    Objects.requireNonNull(dataSource, "dataSource cannot be null");
    this.jdbcTemplate = new JdbcTemplate(dataSource);
    this.codec = new CheckpointJsonCodec();
  }

  @Override
  public void create(SuspensionCheckpoint checkpoint) {
    Objects.requireNonNull(checkpoint, "checkpoint cannot be null");

    String json = codec.serialize(checkpoint);

    try {
      jdbcTemplate.update(
          """
          INSERT INTO arctra_checkpoints
            (process_id, checkpoint_version, schema_version, runtime_binding_key, session_id, checkpoint_data)
          VALUES (?, ?, ?, ?, ?, ?)
          """,
          checkpoint.processId(),
          checkpoint.checkpointVersion(),
          checkpoint.schemaVersion(),
          checkpoint.runtimeBindingKey(),
          checkpoint.sessionId(),
          json);
    } catch (DuplicateKeyException e) {
      throw new CheckpointAlreadyExistsException(checkpoint.processId());
    }
  }

  @Override
  public Optional<SuspensionCheckpoint> load(String processId) {
    Objects.requireNonNull(processId, "processId cannot be null");

    return jdbcTemplate.query(
            """
            SELECT checkpoint_data
            FROM arctra_checkpoints
            WHERE process_id = ?
            """,
            rs -> {
              if (rs.next()) {
                String json = rs.getString("checkpoint_data");
                return Optional.of(codec.deserialize(json));
              }
              return Optional.empty();
            },
            processId);
  }

  @Override
  public boolean replaceIfVersion(
      String processId, long expectedVersion, SuspensionCheckpoint replacement) {
    Objects.requireNonNull(processId, "processId cannot be null");
    Objects.requireNonNull(replacement, "replacement cannot be null");

    if (!replacement.processId().equals(processId)) {
      throw new IllegalArgumentException(
          "Replacement checkpoint processId ("
              + replacement.processId()
              + ") does not match key ("
              + processId
              + ")");
    }

    String json = codec.serialize(replacement);

    int rowsUpdated =
        jdbcTemplate.update(
            """
            UPDATE arctra_checkpoints
            SET checkpoint_version = ?,
                schema_version = ?,
                runtime_binding_key = ?,
                session_id = ?,
                checkpoint_data = ?
            WHERE process_id = ?
              AND checkpoint_version = ?
            """,
            replacement.checkpointVersion(),
            replacement.schemaVersion(),
            replacement.runtimeBindingKey(),
            replacement.sessionId(),
            json,
            processId,
            expectedVersion);

    return rowsUpdated == 1;
  }

  @Override
  public boolean deleteIfVersion(String processId, long expectedVersion) {
    Objects.requireNonNull(processId, "processId cannot be null");

    int rowsDeleted =
        jdbcTemplate.update(
            """
            DELETE FROM arctra_checkpoints
            WHERE process_id = ?
              AND checkpoint_version = ?
            """,
            processId,
            expectedVersion);

    return rowsDeleted == 1;
  }

  /**
   * Get DataSource for internal pairing (package-private).
   *
   * <p>Used by {@link SpringAiToolCallingEngine} to pair with matching {@link
   * JdbcInvocationStateStore}. NOT part of public API.
   *
   * @return the configured DataSource
   */
  DataSource getDataSource() {
    return jdbcTemplate.getDataSource();
  }
}
