package cn.bitcss.arctra.procedure;

import static org.junit.jupiter.api.Assertions.*;

import cn.bitcss.arctra.governance.GovernanceDecision;
import cn.bitcss.arctra.governance.ToolGovernancePolicy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * M8-C 操作治理评估器测试。
 *
 * @author lov3r
 */
class OperationGovernanceEvaluatorTest {

  private OperationGovernanceEvaluator evaluator;

  @BeforeEach
  void setUp() {
    // 使用允许所有的策略
    evaluator = new OperationGovernanceEvaluator(ToolGovernancePolicy.allowAll());
  }

  @Test
  void allowAllPolicyPermitsStep() {
    var step = createTestStep("getService");

    var decision = evaluator.evaluate(step, "{\"serviceName\":\"api\"}");

    assertEquals(GovernanceDecision.ALLOW, decision);
    assertTrue(OperationGovernanceEvaluator.canExecuteImmediately(decision));
  }

  @Test
  void requireApprovalPolicyBlocksImmediateExecution() {
    // 需要批准的策略
    ToolGovernancePolicy requireApprovalPolicy =
        (toolName, args, ctx) -> GovernanceDecision.REQUIRE_APPROVAL;
    evaluator = new OperationGovernanceEvaluator(requireApprovalPolicy);

    var step = createTestStep("deleteResource");

    var decision = evaluator.evaluate(step, "{\"id\":\"123\"}");

    assertEquals(GovernanceDecision.REQUIRE_APPROVAL, decision);
    assertTrue(OperationGovernanceEvaluator.requiresApproval(decision));
    assertFalse(OperationGovernanceEvaluator.canExecuteImmediately(decision));
  }

  @Test
  void denyPolicyRejectsStep() {
    // 拒绝的策略
    ToolGovernancePolicy denyPolicy = (toolName, args, ctx) -> GovernanceDecision.DENY;
    evaluator = new OperationGovernanceEvaluator(denyPolicy);

    var step = createTestStep("dangerousOperation");

    var decision = evaluator.evaluate(step, "{\"force\":true}");

    assertEquals(GovernanceDecision.DENY, decision);
    assertTrue(OperationGovernanceEvaluator.isDenied(decision));
    assertFalse(OperationGovernanceEvaluator.canExecuteImmediately(decision));
  }

  @Test
  void policyChangesAffectCachedProcedure() {
    // 第一次评估：允许
    var step = createTestStep("updateConfig");
    var decision1 = evaluator.evaluate(step, "{\"key\":\"value\"}");
    assertEquals(GovernanceDecision.ALLOW, decision1);

    // 策略改变：现在需要批准
    ToolGovernancePolicy stricterPolicy =
        (toolName, args, ctx) -> GovernanceDecision.REQUIRE_APPROVAL;
    evaluator = new OperationGovernanceEvaluator(stricterPolicy);

    // 第二次评估：相同步骤，不同决策
    var decision2 = evaluator.evaluate(step, "{\"key\":\"value\"}");
    assertEquals(GovernanceDecision.REQUIRE_APPROVAL, decision2);
  }

  @Test
  void evaluatorUsesCurrentPolicyNotHistoricalPolicy() {
    // 创建一个过程步骤（可能是从历史执行中提取的）
    var step = createTestStep("rollbackDeployment");

    // 当前策略拒绝此操作
    ToolGovernancePolicy currentPolicy = (toolName, args, ctx) -> GovernanceDecision.DENY;
    evaluator = new OperationGovernanceEvaluator(currentPolicy);

    // 评估必须使用当前策略，而不是过程创建时的历史策略
    var decision = evaluator.evaluate(step, "{\"version\":\"1.0\"}");

    assertEquals(GovernanceDecision.DENY, decision);
  }

  private ProcedureStep createTestStep(String toolName) {
    var fingerprint = new ToolCompatibilityFingerprint(toolName, "test-hash");
    return new ProcedureStep(0, toolName, fingerprint, Map.of(), List.of());
  }
}
