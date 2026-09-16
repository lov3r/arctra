package cn.bitcss.arctra.runtime.react;

import static org.assertj.core.api.Assertions.*;

import cn.bitcss.arctra.checkpoint.CheckpointStore;
import cn.bitcss.arctra.checkpoint.InMemoryCheckpointStore;
import cn.bitcss.arctra.checkpoint.PendingToolCall;
import cn.bitcss.arctra.checkpoint.SuspensionCheckpoint;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

/**
 * Tests for JDBC durable recovery store pair coherence.
 *
 * <p>Proves that JdbcCheckpointStore + JdbcInvocationStateStore paired through engine work
 * correctly together for restart-durable recovery.
 *
 * @author lov3r
 * @since M6-T4E
 */
@DisplayName("JDBC Durable Recovery Store Pair")
class JdbcDurableRecoveryPairTest {

  private EmbeddedDatabase database;

  @BeforeEach
  void setUp() {
    database =
        new EmbeddedDatabaseBuilder()
            .setType(EmbeddedDatabaseType.H2)
            .addScript("jdbc-durable-recovery-schema.sql")
            .build();
  }

  @AfterEach
  void tearDown() {
    if (database != null) {
      database.shutdown();
    }
  }

  @Test
  @DisplayName("Official JDBC pair: checkpoint and intent both survive restart")
  void officialPairRestart() {
    // Phase 1: Instance A persists both checkpoint and intent
    JdbcCheckpointStore checkpointStoreA = new JdbcCheckpointStore(database);
    JdbcInvocationStateStore intentStoreA = new JdbcInvocationStateStore(database);

    SuspensionCheckpoint checkpoint =
        new SuspensionCheckpoint(
            "1.0",
            "proc-restart-pair",
            1L,
            "key",
            "session",
            List.of(new PendingToolCall("op-durable", "tc-1", "query_logs", "{}")),
            List.of(), "test-epoch");

    checkpointStoreA.create(checkpoint);
    intentStoreA.recordInvocationIntent("proc-restart-pair", "op-durable", "attempt-test");

    // Discard instances A (simulate JVM restart)
    checkpointStoreA = null;
    intentStoreA = null;

    // Phase 2: Instance B (new JVM) sees both
    JdbcCheckpointStore checkpointStoreB = new JdbcCheckpointStore(database);
    JdbcInvocationStateStore intentStoreB = new JdbcInvocationStateStore(database);

    assertThat(checkpointStoreB.load("proc-restart-pair")).isPresent();
    assertThat(intentStoreB.hasInvocationIntent("proc-restart-pair", "op-durable", "attempt-test")).isTrue();
  }

  @Test
  @DisplayName("Same DataSource pairing: both stores use same DB")
  void sameDataSourcePairing() {
    JdbcCheckpointStore checkpointStore = new JdbcCheckpointStore(database);
    JdbcInvocationStateStore intentStore =
        new JdbcInvocationStateStore(checkpointStore.getDataSource());

    // Both stores write to same database
    SuspensionCheckpoint checkpoint =
        new SuspensionCheckpoint(
            "1.0",
            "proc-same-ds",
            1L,
            "key",
            "session",
            List.of(new PendingToolCall("op-1", "tc-1", "tool", "{}")),
            List.of(), "test-epoch");

    checkpointStore.create(checkpoint);
    intentStore.recordInvocationIntent("proc-same-ds", "op-1", "attempt-test");

    // Direct DB query confirms both in same database
    JdbcTemplate jdbc = new JdbcTemplate(database);

    Integer checkpointCount =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM arctra_checkpoints WHERE process_id = ?",
            Integer.class,
            "proc-same-ds");

    Integer intentCount =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM arctra_invocation_intents WHERE process_id = ? AND operation_id = ?",
            Integer.class,
            "proc-same-ds",
            "op-1");

    assertThat(checkpointCount).isEqualTo(1);
    assertThat(intentCount).isEqualTo(1);
  }

  @Test
  @DisplayName("Cross-store read-after-write: checkpoint written, intent immediately readable")
  void crossStoreReadAfterWrite() {
    JdbcCheckpointStore checkpointStore = new JdbcCheckpointStore(database);
    JdbcInvocationStateStore intentStore = new JdbcInvocationStateStore(database);

    // Write checkpoint
    SuspensionCheckpoint checkpoint =
        new SuspensionCheckpoint(
            "1.0",
            "proc-raw",
            1L,
            "key",
            "session",
            List.of(new PendingToolCall("op-raw", "tc-1", "tool", "{}")),
            List.of(), "test-epoch");

    checkpointStore.create(checkpoint);

    // Write intent
    intentStore.recordInvocationIntent("proc-raw", "op-raw", "attempt-test");

    // Independent store instances immediately see committed data
    JdbcCheckpointStore checkpointStore2 = new JdbcCheckpointStore(database);
    JdbcInvocationStateStore intentStore2 = new JdbcInvocationStateStore(database);

    assertThat(checkpointStore2.load("proc-raw")).isPresent();
    assertThat(intentStore2.hasInvocationIntent("proc-raw", "op-raw", "attempt-test")).isTrue();
  }

  @Test
  @DisplayName("Checkpoint deletion does not affect intent (orphan intent safe)")
  void checkpointDeletionLeavesIntent() {
    JdbcCheckpointStore checkpointStore = new JdbcCheckpointStore(database);
    JdbcInvocationStateStore intentStore = new JdbcInvocationStateStore(database);

    SuspensionCheckpoint checkpoint =
        new SuspensionCheckpoint(
            "1.0",
            "proc-orphan",
            1L,
            "key",
            "session",
            List.of(new PendingToolCall("op-orphan", "tc-1", "tool", "{}")),
            List.of(), "test-epoch");

    checkpointStore.create(checkpoint);
    intentStore.recordInvocationIntent("proc-orphan", "op-orphan", "attempt-test");

    // Delete checkpoint (terminal state reached)
    checkpointStore.deleteIfVersion("proc-orphan", 1L);

    // Intent remains (orphan intent is safe - cannot contaminate new operations)
    assertThat(checkpointStore.load("proc-orphan")).isEmpty();
    assertThat(intentStore.hasInvocationIntent("proc-orphan", "op-orphan", "attempt-test")).isTrue();
  }

  @Test
  @DisplayName("Multiple operations: checkpoint contains 3 pending, intents for 2 recorded")
  void multipleOperationsPartialIntent() {
    JdbcCheckpointStore checkpointStore = new JdbcCheckpointStore(database);
    JdbcInvocationStateStore intentStore = new JdbcInvocationStateStore(database);

    SuspensionCheckpoint checkpoint =
        new SuspensionCheckpoint(
            "1.0",
            "proc-multi",
            1L,
            "key",
            "session",
            List.of(
                new PendingToolCall("op-1", "tc-1", "tool-a", "{}"),
                new PendingToolCall("op-2", "tc-2", "tool-b", "{}"),
                new PendingToolCall("op-3", "tc-3", "tool-c", "{}")),
            List.of(), "test-epoch");

    checkpointStore.create(checkpoint);

    // Intents recorded for op-1 and op-2 (op-3 not yet executed)
    intentStore.recordInvocationIntent("proc-multi", "op-1", "attempt-test");
    intentStore.recordInvocationIntent("proc-multi", "op-2", "attempt-test");

    // Verify state
    assertThat(intentStore.hasInvocationIntent("proc-multi", "op-1", "attempt-test")).isTrue();
    assertThat(intentStore.hasInvocationIntent("proc-multi", "op-2", "attempt-test")).isTrue();
    assertThat(intentStore.hasInvocationIntent("proc-multi", "op-3", "attempt-test")).isFalse();
  }

  @Test
  @DisplayName("In-memory checkpoint + JDBC intent: execution-compatible but not restart-durable")
  void inMemoryCheckpointJdbcIntent() {
    CheckpointStore inMemoryCheckpoint = new InMemoryCheckpointStore();
    JdbcInvocationStateStore jdbcIntent = new JdbcInvocationStateStore(database);

    SuspensionCheckpoint checkpoint =
        new SuspensionCheckpoint(
            "1.0",
            "proc-mixed",
            1L,
            "key",
            "session",
            List.of(new PendingToolCall("op-mixed", "tc-1", "tool", "{}")),
            List.of(), "test-epoch");

    inMemoryCheckpoint.create(checkpoint);
    jdbcIntent.recordInvocationIntent("proc-mixed", "op-mixed", "attempt-test");

    // Execution-compatible: both stores work
    assertThat(inMemoryCheckpoint.load("proc-mixed")).isPresent();
    assertThat(jdbcIntent.hasInvocationIntent("proc-mixed", "op-mixed", "attempt-test")).isTrue();

    // But after "restart" (discard in-memory store):
    inMemoryCheckpoint = new InMemoryCheckpointStore();

    // Checkpoint lost, intent remains (useless but safe)
    assertThat(inMemoryCheckpoint.load("proc-mixed")).isEmpty();
    assertThat(jdbcIntent.hasInvocationIntent("proc-mixed", "op-mixed", "attempt-test")).isTrue();
  }
}
