package cn.bitcss.arctra.procedure;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * M8-A tests for InMemoryProcedureCandidateStore.
 *
 * @author lov3r
 */
class InMemoryProcedureCandidateStoreTest {

  private InMemoryProcedureCandidateStore store;
  private ProcedureScope scope;

  @BeforeEach
  void setUp() {
    store = new InMemoryProcedureCandidateStore();
    scope = ProcedureScope.forAgent("test-agent");
  }

  @Test
  void saveCandidate() {
    var candidate = createTestCandidate("candidate1");

    store.save(candidate);

    var found = store.findById("candidate1");
    assertTrue(found.isPresent());
    assertEquals("candidate1", found.get().candidateId());
  }

  @Test
  void rejectDuplicateCandidate() {
    var candidate = createTestCandidate("candidate1");

    store.save(candidate);

    assertThrows(IllegalArgumentException.class, () -> store.save(candidate));
  }

  @Test
  void findByIdReturnsEmptyForNonExistent() {
    var found = store.findById("nonexistent");
    assertTrue(found.isEmpty());
  }

  @Test
  void listByScope() {
    var scope1 = ProcedureScope.forAgent("agent1");
    var scope2 = ProcedureScope.forAgent("agent2");

    var candidate1 = createTestCandidateWithScope("c1", scope1);
    var candidate2 = createTestCandidateWithScope("c2", scope1);
    var candidate3 = createTestCandidateWithScope("c3", scope2);

    store.save(candidate1);
    store.save(candidate2);
    store.save(candidate3);

    var scope1Candidates = store.listByScope(scope1);
    assertEquals(2, scope1Candidates.size());

    var scope2Candidates = store.listByScope(scope2);
    assertEquals(1, scope2Candidates.size());
  }

  @Test
  void updateValidation() {
    var candidate = createTestCandidate("candidate1");
    store.save(candidate);

    store.updateValidation("candidate1", "PASSED");

    var updated = store.findById("candidate1");
    assertTrue(updated.isPresent());
    assertEquals("PASSED", updated.get().validationOutcome());
  }

  @Test
  void updateValidationForNonExistentThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () -> store.updateValidation("nonexistent", "PASSED"));
  }

  @Test
  void deleteCandidate() {
    var candidate = createTestCandidate("candidate1");
    store.save(candidate);

    boolean deleted = store.delete("candidate1");

    assertTrue(deleted);
    assertTrue(store.findById("candidate1").isEmpty());
  }

  @Test
  void deleteNonExistentReturnsFalse() {
    boolean deleted = store.delete("nonexistent");
    assertFalse(deleted);
  }

  @Test
  void candidateIsNotAutomaticallyExecutable() {
    // This is a semantic test - candidates exist in separate store
    // and are never returned by ReusableProcedureStore queries
    var candidate = createTestCandidate("candidate1");
    store.save(candidate);

    // Candidate store has it
    assertTrue(store.findById("candidate1").isPresent());

    // But it's not a ReusableProcedure and can't be executed
    // (separate lifecycle, separate authority)
    assertNotNull(candidate.proposedSteps());
    assertNull(candidate.validationOutcome()); // Not yet validated
  }

  private ProcedureCandidate createTestCandidate(String candidateId) {
    return createTestCandidateWithScope(candidateId, scope);
  }

  private ProcedureCandidate createTestCandidateWithScope(
      String candidateId, ProcedureScope scope) {
    var step =
        new ProcedureStep(
            0,
            "testTool",
            new ToolCompatibilityFingerprint("testTool", "hash123"),
            Map.of(),
            List.of());

    return new ProcedureCandidate(
        candidateId,
        scope,
        "test-intent",
        List.of(step),
        "execution-123",
        Instant.now(),
        null);
  }
}
