package cn.bitcss.arctra.procedure;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * M8-A tests for ReusableProcedure domain model.
 *
 * @author lov3r
 */
class ReusableProcedureTest {

  @Test
  void createValidProcedure() {
    var scope = ProcedureScope.forAgent("incident-investigator");
    var step =
        new ProcedureStep(
            0,
            "getService",
            new ToolCompatibilityFingerprint("getService", "sha256-abc123"),
            Map.of("serviceName", new ParameterBinding(BindingSource.INPUT, "serviceName")),
            List.of(new OutputExtraction("serviceId", "/id")));

    var procedure =
        new ReusableProcedure(
            "investigate-latency",
            1,
            scope,
            "investigate-service-latency",
            List.of(step),
            ProcedureStatus.VALID,
            Instant.now(),
            null);

    assertEquals("investigate-latency", procedure.procedureId());
    assertEquals(1, procedure.revision());
    assertEquals(ProcedureStatus.VALID, procedure.status());
    assertEquals(1, procedure.steps().size());
  }

  @Test
  void rejectEmptyProcedureId() {
    var scope = ProcedureScope.forAgent("test");
    var step =
        new ProcedureStep(
            0,
            "tool",
            new ToolCompatibilityFingerprint("tool", "hash"),
            Map.of(),
            List.of());

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ReusableProcedure(
                "", 1, scope, "intent", List.of(step), ProcedureStatus.VALID, Instant.now(), null));
  }

  @Test
  void rejectZeroRevision() {
    var scope = ProcedureScope.forAgent("test");
    var step =
        new ProcedureStep(
            0,
            "tool",
            new ToolCompatibilityFingerprint("tool", "hash"),
            Map.of(),
            List.of());

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ReusableProcedure(
                "proc",
                0,
                scope,
                "intent",
                List.of(step),
                ProcedureStatus.VALID,
                Instant.now(),
                null));
  }

  @Test
  void rejectEmptySteps() {
    var scope = ProcedureScope.forAgent("test");

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ReusableProcedure(
                "proc",
                1,
                scope,
                "intent",
                List.of(),
                ProcedureStatus.VALID,
                Instant.now(),
                null));
  }

  @Test
  void rejectNonContiguousStepIndexes() {
    var scope = ProcedureScope.forAgent("test");
    var step1 =
        new ProcedureStep(
            0,
            "tool1",
            new ToolCompatibilityFingerprint("tool1", "hash1"),
            Map.of(),
            List.of());
    var step2 =
        new ProcedureStep(
            2, // Should be 1
            "tool2",
            new ToolCompatibilityFingerprint("tool2", "hash2"),
            Map.of(),
            List.of());

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ReusableProcedure(
                "proc",
                1,
                scope,
                "intent",
                List.of(step1, step2),
                ProcedureStatus.VALID,
                Instant.now(),
                null));
  }

  @Test
  void createNextRevision() {
    var scope = ProcedureScope.forAgent("test");
    var step =
        new ProcedureStep(
            0,
            "tool",
            new ToolCompatibilityFingerprint("tool", "hash"),
            Map.of(),
            List.of());

    var rev1 =
        new ReusableProcedure(
            "proc", 1, scope, "intent", List.of(step), ProcedureStatus.VALID, Instant.now(), null);

    var newStep =
        new ProcedureStep(
            0,
            "tool2",
            new ToolCompatibilityFingerprint("tool2", "hash2"),
            Map.of(),
            List.of());

    var rev2 = rev1.createNextRevision(List.of(newStep), "execution-123");

    assertEquals("proc", rev2.procedureId());
    assertEquals(2, rev2.revision());
    assertEquals("execution-123", rev2.derivedFrom());
    assertEquals(ProcedureStatus.VALID, rev2.status());
  }

  @Test
  void updateStatus() {
    var scope = ProcedureScope.forAgent("test");
    var step =
        new ProcedureStep(
            0,
            "tool",
            new ToolCompatibilityFingerprint("tool", "hash"),
            Map.of(),
            List.of());

    var procedure =
        new ReusableProcedure(
            "proc", 1, scope, "intent", List.of(step), ProcedureStatus.VALID, Instant.now(), null);

    var updated = procedure.withStatus(ProcedureStatus.INVALID);

    assertEquals(ProcedureStatus.INVALID, updated.status());
    assertEquals("proc", updated.procedureId());
    assertEquals(1, updated.revision());
  }

  @Test
  void twoStepProcedureWithOutputBinding() {
    var scope = ProcedureScope.forAgent("incident-investigator");

    var step1 =
        new ProcedureStep(
            0,
            "getService",
            new ToolCompatibilityFingerprint("getService", "hash1"),
            Map.of("serviceName", new ParameterBinding(BindingSource.INPUT, "serviceName")),
            List.of(new OutputExtraction("serviceId", "/id")));

    var step2 =
        new ProcedureStep(
            1,
            "queryLogs",
            new ToolCompatibilityFingerprint("queryLogs", "hash2"),
            Map.of(
                "serviceId", new ParameterBinding(BindingSource.PREVIOUS_STEP_OUTPUT, "0.serviceId")),
            List.of());

    var procedure =
        new ReusableProcedure(
            "investigate",
            1,
            scope,
            "investigate-service-latency",
            List.of(step1, step2),
            ProcedureStatus.VALID,
            Instant.now(),
            null);

    assertEquals(2, procedure.steps().size());
    assertEquals("queryLogs", procedure.steps().get(1).toolName());
  }

  @Test
  void rejectUndefinedOutputReference() {
    var scope = ProcedureScope.forAgent("test");

    var step1 =
        new ProcedureStep(
            0,
            "tool1",
            new ToolCompatibilityFingerprint("tool1", "hash1"),
            Map.of(),
            List.of(new OutputExtraction("output1", "/field1")));

    var step2 =
        new ProcedureStep(
            1,
            "tool2",
            new ToolCompatibilityFingerprint("tool2", "hash2"),
            Map.of(
                "param",
                new ParameterBinding(
                    BindingSource.PREVIOUS_STEP_OUTPUT, "0.nonExistentOutput")),
            List.of());

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ReusableProcedure(
                "proc",
                1,
                scope,
                "intent",
                List.of(step1, step2),
                ProcedureStatus.VALID,
                Instant.now(),
                null));
  }
}
