package cn.bitcss.arctra.procedure;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * InMemoryReusableProcedureStore 测试。
 *
 * @author lov3r
 */
class InMemoryReusableProcedureStoreTest {

  private InMemoryReusableProcedureStore store;

  @BeforeEach
  void setUp() {
    store = new InMemoryReusableProcedureStore();
  }

  @Test
  void saveProcedure() {
    var procedure = createTestProcedure("proc-1", 1);

    store.createRevision(procedure);

    assertEquals(1, store.size());
    var found = store.findLatest("proc-1");
    assertTrue(found.isPresent());
    assertEquals(1, found.get().revision());
  }

  @Test
  void saveMultipleRevisions() {
    store.createRevision(createTestProcedure("proc-1", 1));
    store.createRevision(createTestProcedure("proc-1", 2));
    store.createRevision(createTestProcedure("proc-1", 3));

    assertEquals(3, store.size());

    var latest = store.findLatest("proc-1");
    assertTrue(latest.isPresent());
    assertEquals(3, latest.get().revision());
  }

  @Test
  void findByRevision() {
    store.createRevision(createTestProcedure("proc-1", 1));
    store.createRevision(createTestProcedure("proc-1", 2));

    var found = store.findRevision("proc-1", 1);
    assertTrue(found.isPresent());
    assertEquals(1, found.get().revision());
  }

  @Test
  void rejectDuplicateRevision() {
    store.createRevision(createTestProcedure("proc-1", 1));

    assertThrows(
        IllegalArgumentException.class,
        () -> store.createRevision(createTestProcedure("proc-1", 1)));
  }

  @Test
  void findLatestReturnsEmpty() {
    var found = store.findLatest("nonexistent");
    assertTrue(found.isEmpty());
  }

  @Test
  void findByScope() {
    var scope1 = ProcedureScope.forAgent("agent-1");
    var scope2 = ProcedureScope.forAgent("agent-2");

    store.createRevision(createTestProcedure("proc-1", 1, scope1));
    store.createRevision(createTestProcedure("proc-2", 1, scope2));

    var found = store.findByScope(scope1);
    assertEquals(1, found.size());
    assertEquals("proc-1", found.get(0).procedureId());
  }

  @Test
  void updateStatus() {
    store.createRevision(createTestProcedure("proc-1", 1));

    store.updateStatus("proc-1", 1, ProcedureStatus.INVALID);

    var found = store.findLatest("proc-1");
    assertTrue(found.isPresent());
    assertEquals(ProcedureStatus.INVALID, found.get().status());
  }

  @Test
  void findAll() {
    store.createRevision(createTestProcedure("proc-1", 1));
    store.createRevision(createTestProcedure("proc-2", 1));

    var all = store.findAll();
    assertEquals(2, all.size());
  }

  private ReusableProcedure createTestProcedure(String procedureId, int revision) {
    return createTestProcedure(procedureId, revision, ProcedureScope.forAgent("test-agent"));
  }

  private ReusableProcedure createTestProcedure(
      String procedureId, int revision, ProcedureScope scope) {
    var fingerprint = new ToolCompatibilityFingerprint("testTool", "hash1");
    var step = new ProcedureStep(0, "testTool", fingerprint, Map.of(), List.of());

    return new ReusableProcedure(
        procedureId,
        revision,
        scope,
        "test-intent",
        List.of(step),
        ProcedureStatus.VALID,
        Instant.now(),
        null);
  }
}
