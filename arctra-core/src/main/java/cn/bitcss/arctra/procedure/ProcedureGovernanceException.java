package cn.bitcss.arctra.procedure;

/**
 * 过程治理异常 - 当治理策略拒绝过程步骤执行时抛出。
 *
 * <p><strong>M8-C：</strong>当缓存过程步骤被当前治理策略拒绝（DENY）时，过程执行必须失败。
 *
 * <p>这表示：
 *
 * <ul>
 *   <li>策略已改变（以前允许的操作现在被拒绝）
 *   <li>过程应该失效或需要修订
 *   <li>不能回退到 ReAct（因为操作本身被策略禁止）
 * </ul>
 *
 * @author lov3r
 * @since M8-C
 */
public class ProcedureGovernanceException extends RuntimeException {

  private final String procedureId;
  private final int revision;
  private final int stepIndex;
  private final String toolName;

  public ProcedureGovernanceException(
      String procedureId, int revision, int stepIndex, String toolName, String message) {
    super(
        String.format(
            "Governance rejected procedure step: %s revision %d step %d tool '%s' - %s",
            procedureId, revision, stepIndex, toolName, message));
    this.procedureId = procedureId;
    this.revision = revision;
    this.stepIndex = stepIndex;
    this.toolName = toolName;
  }

  public String procedureId() {
    return procedureId;
  }

  public int revision() {
    return revision;
  }

  public int stepIndex() {
    return stepIndex;
  }

  public String toolName() {
    return toolName;
  }
}
