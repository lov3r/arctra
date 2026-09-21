package cn.bitcss.arctra.procedure;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * SimpleProcedureMatcher 测试。
 *
 * @author lov3r
 */
class SimpleProcedureMatcherTest {

  private SimpleProcedureMatcher matcher;
  private InMemoryReusableProcedureStore store;

  @BeforeEach
  void setUp() {
    store = new InMemoryReusableProcedureStore();
    matcher = new SimpleProcedureMatcher(store);
  }

  @Test
  void matchByAgentName() {
    // 创建一个 VALID 过程
    var scope = ProcedureScope.forAgent("test-agent");
    var procedure = createTestProcedure("proc-1", 1, scope, "test-agent", ProcedureStatus.VALID);
    store.createRevision(procedure);

    var result = matcher.findMatch("test-agent", "any user prompt");

    assertTrue(result.isPresent());
    assertEquals("proc-1", result.get().procedureId());
  }

  @Test
  void returnEmptyWhenNoMatch() {
    var result = matcher.findMatch("unknown-agent", "any prompt");
    assertTrue(result.isEmpty());
  }

  @Test
  void returnLatestValidRevision() {
    var scope = ProcedureScope.forAgent("test-agent");

    // 创建多个修订
    store.createRevision(createTestProcedure("proc-1", 1, scope, "test-agent", ProcedureStatus.VALID));
    store.createRevision(createTestProcedure("proc-1", 2, scope, "test-agent", ProcedureStatus.VALID));
    store.createRevision(createTestProcedure("proc-1", 3, scope, "test-agent", ProcedureStatus.VALID));

    var result = matcher.findMatch("test-agent", "any prompt");

    assertTrue(result.isPresent());
    assertEquals(3, result.get().revision());
  }

  @Test
  void ignoreInvalidProcedures() {
    var scope = ProcedureScope.forAgent("test-agent");

    // 创建 VALID 和 INVALID 修订
    store.createRevision(createTestProcedure("proc-1", 1, scope, "test-agent", ProcedureStatus.VALID));
    store.createRevision(createTestProcedure("proc-1", 2, scope, "test-agent", ProcedureStatus.INVALID));

    var result = matcher.findMatch("test-agent", "any prompt");

    assertTrue(result.isPresent());
    assertEquals(1, result.get().revision()); // 返回 VALID 的修订
  }

  @Test
  void returnEmptyWhenOnlyInvalidExists() {
    var scope = ProcedureScope.forAgent("test-agent");
    store.createRevision(createTestProcedure("proc-1", 1, scope, "test-agent", ProcedureStatus.INVALID));

    var result = matcher.findMatch("test-agent", "any prompt");

    assertTrue(result.isEmpty());
  }

  @Test
  void matchDifferentAgents() {
    var scope1 = ProcedureScope.forAgent("agent-1");
    var scope2 = ProcedureScope.forAgent("agent-2");

    store.createRevision(createTestProcedure("proc-1", 1, scope1, "agent-1", ProcedureStatus.VALID));
    store.createRevision(createTestProcedure("proc-2", 1, scope2, "agent-2", ProcedureStatus.VALID));

    var result1 = matcher.findMatch("agent-1", "prompt");
    var result2 = matcher.findMatch("agent-2", "prompt");

    assertTrue(result1.isPresent());
    assertTrue(result2.isPresent());
    assertEquals("proc-1", result1.get().procedureId());
    assertEquals("proc-2", result2.get().procedureId());
  }

  private ReusableProcedure createTestProcedure(
      String procedureId, int revision, ProcedureScope scope,
      String intentKey, ProcedureStatus status) {
    var fingerprint = new ToolCompatibilityFingerprint("testTool", "hash1");
    var step = new ProcedureStep(0, "testTool", fingerprint, Map.of(), List.of());

    return new ReusableProcedure(
        procedureId,
        revision,
        scope,
        intentKey,
        List.of(step),
        status,
        Instant.now(),
        null);
  }
}
