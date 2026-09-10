package cn.bitcss.arctra.runtime.react;

/**
 * Internal control signal for tool approval suspension.
 *
 * <p>This exception represents normal control flow, not an error. It carries suspension state from
 * {@link GovernanceToolCallingAdvisor} to {@link SpringAiToolCallingEngine} without polluting
 * conversational memory with synthetic placeholder messages.
 *
 * <p><b>Package-private</b> - internal to arctra-runtime-react only. Not part of public API.
 *
 * <p><b>Semantics:</b>
 * <ul>
 *   <li>WAITING is AgentProcess lifecycle state, not a conversational Assistant response</li>
 *   <li>Throwing this signal prevents MessageChatMemoryAdvisor.after() from persisting
 *       a synthetic suspension placeholder</li>
 *   <li>Suspended execution leaves an "open turn": User message persisted, Assistant pending</li>
 *   <li>Final Assistant message is persisted only when Process reaches true COMPLETED state</li>
 * </ul>
 *
 * @author arctra
 */
final class ToolApprovalRequiredSignal extends RuntimeException {

  private final GovernanceToolCallingAdvisor.SuspensionState state;

  /**
   * Constructs suspension signal carrying complete batch state.
   *
   * @param state the suspension state (batch of tool calls requiring approval)
   */
  ToolApprovalRequiredSignal(GovernanceToolCallingAdvisor.SuspensionState state) {
    // Disable stack trace - this is expected control flow, not an error
    super("Tool approval required", null, false, false);
    this.state = state;
  }

  /**
   * Returns the suspension state for this approval requirement.
   *
   * @return suspension state containing original request and tool call batch
   */
  GovernanceToolCallingAdvisor.SuspensionState state() {
    return state;
  }
}
