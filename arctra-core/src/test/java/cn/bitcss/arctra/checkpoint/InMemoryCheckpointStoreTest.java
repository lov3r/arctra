package cn.bitcss.arctra.checkpoint;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link InMemoryCheckpointStore} CAS semantics.
 *
 * @author lov3r
 * @since M5-T4
 */
class InMemoryCheckpointStoreTest {

  private InMemoryCheckpointStore store;

  @BeforeEach
  void setUp() {
    store = new InMemoryCheckpointStore();
  }

  @Test
  void create_newCheckpoint_succeeds() {
    SuspensionCheckpoint checkpoint = createCheckpoint("P100", 1L);

    assertDoesNotThrow(() -> store.create(checkpoint));

    Optional<SuspensionCheckpoint> loaded = store.load("P100");
    assertTrue(loaded.isPresent());
    assertEquals(1L, loaded.get().checkpointVersion());
  }

  @Test
  void create_duplicateProcessId_throws() {
    SuspensionCheckpoint checkpoint1 = createCheckpoint("P100", 1L);
    SuspensionCheckpoint checkpoint2 = createCheckpoint("P100", 2L);

    store.create(checkpoint1);

    assertThrows(CheckpointAlreadyExistsException.class, () -> store.create(checkpoint2));
  }

  @Test
  void replaceIfVersion_correctVersion_succeeds() {
    SuspensionCheckpoint v1 = createCheckpoint("P100", 1L);
    SuspensionCheckpoint v2 = createCheckpoint("P100", 2L);

    store.create(v1);

    boolean replaced = store.replaceIfVersion("P100", 1L, v2);

    assertTrue(replaced, "Should succeed with correct version");
    assertEquals(2L, store.load("P100").get().checkpointVersion());
  }

  @Test
  void replaceIfVersion_wrongVersion_returnsFalse() {
    SuspensionCheckpoint v1 = createCheckpoint("P100", 1L);
    SuspensionCheckpoint v2 = createCheckpoint("P100", 2L);

    store.create(v1);

    boolean replaced = store.replaceIfVersion("P100", 999L, v2);

    assertFalse(replaced, "Should fail with wrong version");
    assertEquals(1L, store.load("P100").get().checkpointVersion(), "Checkpoint should be unchanged");
  }

  @Test
  void replaceIfVersion_versionMismatchSameObject_returnsFalse() {
    SuspensionCheckpoint v1 = createCheckpoint("P100", 5L);

    store.create(v1);

    // Attempt to replace with wrong version but same object
    boolean replaced = store.replaceIfVersion("P100", 999L, v1);

    assertFalse(replaced, "Should return false even with same object when version mismatches");
    assertEquals(5L, store.load("P100").get().checkpointVersion(), "Checkpoint should be unchanged");
  }

  @Test
  void replaceIfVersion_missingCheckpoint_returnsFalse() {
    SuspensionCheckpoint v1 = createCheckpoint("P100", 1L);

    boolean replaced = store.replaceIfVersion("P100", 1L, v1);

    assertFalse(replaced, "Should fail when checkpoint does not exist");
    assertFalse(store.load("P100").isPresent(), "Checkpoint should not be created");
  }

  @Test
  void replaceIfVersion_crossProcessId_throws() {
    SuspensionCheckpoint v1P100 = createCheckpoint("P100", 1L);
    SuspensionCheckpoint v1P200 = createCheckpoint("P200", 1L);

    store.create(v1P100);

    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class,
            () -> store.replaceIfVersion("P100", 1L, v1P200),
            "Should reject replacement with different processId");

    assertTrue(
        exception.getMessage().contains("processId"),
        "Exception should mention processId mismatch");

    // Verify P100 unchanged
    assertEquals("P100", store.load("P100").get().processId());
    assertEquals(1L, store.load("P100").get().checkpointVersion());
  }

  @Test
  void deleteIfVersion_correctVersion_succeeds() {
    SuspensionCheckpoint v1 = createCheckpoint("P100", 1L);

    store.create(v1);

    boolean deleted = store.deleteIfVersion("P100", 1L);

    assertTrue(deleted, "Should succeed with correct version");
    assertFalse(store.load("P100").isPresent(), "Checkpoint should be deleted");
  }

  @Test
  void deleteIfVersion_wrongVersion_returnsFalse() {
    SuspensionCheckpoint v1 = createCheckpoint("P100", 1L);

    store.create(v1);

    boolean deleted = store.deleteIfVersion("P100", 999L);

    assertFalse(deleted, "Should fail with wrong version");
    assertTrue(store.load("P100").isPresent(), "Checkpoint should still exist");
    assertEquals(1L, store.load("P100").get().checkpointVersion());
  }

  @Test
  void deleteIfVersion_missingCheckpoint_returnsFalse() {
    boolean deleted = store.deleteIfVersion("P100", 1L);

    assertFalse(deleted, "Should fail when checkpoint does not exist");
  }

  // Helper method
  private SuspensionCheckpoint createCheckpoint(String processId, long version) {
    return new SuspensionCheckpoint(
        "1.0",
        processId,
        version,
        "test-binding-key",
        "session-123",
        List.of(new PendingToolCall("tc-1", "testTool", "{}")),
        List.of());
  }
}
