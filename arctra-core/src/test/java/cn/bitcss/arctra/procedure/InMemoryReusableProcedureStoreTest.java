package cn.bitcss.arctra.procedure;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * M8-A tests for InMemoryReusableProcedureStore.
 *
 * @author lov3r
 */
class InMemoryReusableProcedureStoreTest {

  private InMemoryReusableProcedureStore store;
  private ProcedureScope scope;

  @BeforeEach
  void setUp() {
    store = new InMemoryReusableProcedureStore();
    scope = ProcedureScope.forAgent("test-agent");
  }

  @Test
  void createRevision() {
    var procedure = createTestProcedure("proc1", 1);

    store.createRevision(procedure);

    var found = store.findRevision("proc1", 1);
    assertTrue(found.isPresent());
    assertEquals("proc1", found.get().procedureId());
    assertEquals(1, found.get().revision());
  }

  @Test
  void rejectDuplicateRevision() {
    var procedure = createTestProcedure("proc1", 1);

    store.createRevision(procedure);

    assertThrows(ProcedureAlreadyExistsException.class, () -> store.createRevision(procedure));
  }

  @Test
  void sameProcedureIdCanHaveMultipleRevisions() {
    var rev1 = createTestProcedure("proc1", 1);
    var rev2 = createTestProcedure("proc1", 2);

    store.createRevision(rev1);
    store.createRevision(rev2);

    assertTrue(store.findRevision("proc1", 1).isPresent());
    assertTrue(store.findRevision("proc1", 2).isPresent());
  }

  @Test
  void findNonExistentRevisionReturnsEmpty() {
    var found = store.findRevision("nonexistent", 1);
    assertTrue(found.isEmpty());
  }

  @Test
  void listRevisions() {
    var rev1 = createTestProcedure("proc1", 1);
    var rev2 = createTestProcedure("proc1", 2);
    var rev3 = createTestProcedure("proc1", 3);

    store.createRevision(rev1);
    store.createRevision(rev3); // Out of order
    store.createRevision(rev2);

    var revisions = store.listRevisions("proc1");

    assertEquals(3, revisions.size());
    assertEquals(1, revisions.get(0).revision());
    assertEquals(2, revisions.get(1).revision());
    assertEquals(3, revisions.get(2).revision());
  }

  @Test
  void listRevisionsForNonExistentProcedureReturnsEmpty() {
    var revisions = store.listRevisions("nonexistent");
    assertTrue(revisions.isEmpty());
  }

  @Test
  void findByIntent() {
    var proc1 = createTestProcedureWithIntent("proc1", 1, "investigate-latency");
    var proc2 = createTestProcedureWithIntent("proc2", 1, "investigate-latency");
    var proc3 = createTestProcedureWithIntent("proc3", 1, "different-intent");

    store.createRevision(proc1);
    store.createRevision(proc2);
    store.createRevision(proc3);

    var found = store.findByIntent(scope, "investigate-latency");

    assertEquals(2, found.size());
    assertTrue(found.stream().anyMatch(p -> p.procedureId().equals("proc1")));
    assertTrue(found.stream().anyMatch(p -> p.procedureId().equals("proc2")));
  }

  @Test
  void findByIntentReturnsMultipleRevisions() {
    var proc1rev1 = createTestProcedureWithIntent("proc1", 1, "intent1");
    var proc1rev2 = createTestProcedureWithIntent("proc1", 2, "intent1");

    store.createRevision(proc1rev1);
    store.createRevision(proc1rev2);

    var found = store.findByIntent(scope, "intent1");

    assertEquals(2, found.size());
  }

  @Test
  void updateStatus() {
    var procedure = createTestProcedure("proc1", 1);
    store.createRevision(procedure);

    store.updateStatus("proc1", 1, ProcedureStatus.INVALID);

    var updated = store.findRevision("proc1", 1);
    assertTrue(updated.isPresent());
    assertEquals(ProcedureStatus.INVALID, updated.get().status());
  }

  @Test
  void updateStatusForNonExistentRevisionThrows() {
    assertThrows(
        ProcedureNotFoundException.class,
        () -> store.updateStatus("nonexistent", 1, ProcedureStatus.INVALID));
  }

  @Test
  void oldRevisionRemainsReadableAfterNewRevision() {
    var rev1 = createTestProcedure("proc1", 1);
    var rev2 = createTestProcedure("proc1", 2);

    store.createRevision(rev1);
    store.updateStatus("proc1", 1, ProcedureStatus.SUPERSEDED);
    store.createRevision(rev2);

    var oldRev = store.findRevision("proc1", 1);
    assertTrue(oldRev.isPresent());
    assertEquals(ProcedureStatus.SUPERSEDED, oldRev.get().status());

    var newRev = store.findRevision("proc1", 2);
    assertTrue(newRev.isPresent());
    assertEquals(ProcedureStatus.VALID, newRev.get().status());
  }

  private ReusableProcedure createTestProcedure(String procedureId, int revision) {
    return createTestProcedureWithIntent(procedureId, revision, "test-intent");
  }

  private ReusableProcedure createTestProcedureWithIntent(
      String procedureId, int revision, String intentKey) {
    var step =
        new ProcedureStep(
            0,
            "testTool",
            new ToolCompatibilityFingerprint("testTool", "hash123"),
            Map.of(),
            List.of());

    return new ReusableProcedure(
        procedureId,
        revision,
        scope,
        intentKey,
        List.of(step),
        ProcedureStatus.VALID,
        Instant.now(),
        null);
  }
}
