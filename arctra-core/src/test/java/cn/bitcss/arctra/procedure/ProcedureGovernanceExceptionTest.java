package cn.bitcss.arctra.procedure;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * M8-C 过程治理异常测试。
 *
 * @author lov3r
 */
class ProcedureGovernanceExceptionTest {

  @Test
  void exceptionContainsContextualInformation() {
    var ex =
        new ProcedureGovernanceException(
            "proc-123", 2, 3, "deleteResource", "Policy denies destructive operations");

    assertEquals("proc-123", ex.procedureId());
    assertEquals(2, ex.revision());
    assertEquals(3, ex.stepIndex());
    assertEquals("deleteResource", ex.toolName());
    assertTrue(ex.getMessage().contains("proc-123"));
    assertTrue(ex.getMessage().contains("revision 2"));
    assertTrue(ex.getMessage().contains("step 3"));
    assertTrue(ex.getMessage().contains("deleteResource"));
  }

  @Test
  void exceptionMessageIsDescriptive() {
    var ex =
        new ProcedureGovernanceException(
            "proc-456", 1, 0, "rollback", "Insufficient permissions");

    String message = ex.getMessage();
    assertTrue(message.contains("Governance rejected"));
    assertTrue(message.contains("proc-456"));
    assertTrue(message.contains("rollback"));
    assertTrue(message.contains("Insufficient permissions"));
  }
}
