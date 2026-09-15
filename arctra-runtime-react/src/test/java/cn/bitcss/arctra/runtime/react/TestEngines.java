package cn.bitcss.arctra.runtime.react;

import cn.bitcss.arctra.checkpoint.CheckpointStore;
import cn.bitcss.arctra.checkpoint.InMemoryCheckpointStore;
import cn.bitcss.arctra.execution.ExecutionLedger;
import cn.bitcss.arctra.governance.GovernanceDecision;
import cn.bitcss.arctra.governance.ToolGovernancePolicy;
import cn.bitcss.arctra.runtime.RuntimeBindingResolver;
import java.util.List;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;

/**
 * Test fixture factory for SpringAiToolCallingEngine.
 *
 * <p>Provides standard test configurations to reduce repetitive setup and isolate tests from
 * constructor signature changes.
 *
 * <p><strong>Design Principles:</strong>
 *
 * <ul>
 *   <li>Sensible defaults for non-critical dependencies
 *   <li>Explicit parameters for test-specific concerns
 *   <li>Types remain explicit (no Object... varargs abuse)
 *   <li>Does NOT hide business behavior
 *   <li>Does NOT create complex DSL
 * </ul>
 *
 * <p><strong>When NOT to use:</strong>
 *
 * <ul>
 *   <li>Tests verifying specific collaborator behavior (e.g., checkpoint conflicts, binding
 *       failures)
 *   <li>Tests needing explicit control of all dependencies (e.g., Counting* pattern)
 *   <li>Concurrency tests with shared state tracking
 * </ul>
 *
 * @author lov3r
 * @since M6-T2.5A-R1
 */
public final class TestEngines {

  private TestEngines() {
    throw new UnsupportedOperationException("Utility class");
  }

  /**
   * Create ephemeral (non-durable) engine with minimal setup.
   *
   * <p>Uses:
   *
   * <ul>
   *   <li>Simple ChatModel (echoes last user message)
   *   <li>In-memory ChatMemory (window size 100)
   *   <li>Allow-all governance policy
   * </ul>
   *
   * @param tools tool callbacks (varargs for convenience)
   * @return ephemeral engine
   */
  public static SpringAiToolCallingEngine ephemeral(ToolCallback... tools) {
    return new SpringAiToolCallingEngine(
        simpleChatModel(), List.of(tools), inMemoryChatMemory(), allowAllPolicy());
  }

  /**
   * Create durable engine with explicit checkpoint store and binding resolver.
   *
   * <p>Uses:
   *
   * <ul>
   *   <li>Simple ChatModel (echoes last user message)
   *   <li>In-memory ChatMemory (window size 100)
   *   <li>Allow-all governance policy
   *   <li>Standard binding key "test-binding-key"
   * </ul>
   *
   * @param store checkpoint store (explicit for test control)
   * @param resolver runtime binding resolver (explicit for test control)
   * @param tools tool callbacks
   * @return durable engine
   */
  public static SpringAiToolCallingEngine durable(
      CheckpointStore store, RuntimeBindingResolver resolver, ToolCallback... tools) {
    return new SpringAiToolCallingEngine(
        simpleChatModel(),
        List.of(tools),
        inMemoryChatMemory(),
        allowAllPolicy(),
        store,
        resolver,
        "test-binding-key");
  }

  /**
   * Create durable engine with execution ledger (most common durable test pattern).
   *
   * <p>Uses:
   *
   * <ul>
   *   <li>Simple ChatModel
   *   <li>In-memory ChatMemory
   *   <li>Allow-all governance
   *   <li>In-memory CheckpointStore (new instance)
   *   <li>Standard RuntimeBindingResolver (from TestBindings)
   *   <li>Standard binding key
   * </ul>
   *
   * <p>This is the highest-frequency pattern for lifecycle/tool event tests.
   *
   * @param ledger execution ledger (explicit for test assertions)
   * @param tools tool callbacks
   * @return durable engine with ledger
   */
  public static SpringAiToolCallingEngine durableWithLedger(
      ExecutionLedger ledger, ToolCallback... tools) {
    return new SpringAiToolCallingEngine(
        simpleChatModel(),
        List.of(tools),
        inMemoryChatMemory(),
        allowAllPolicy(),
        new InMemoryCheckpointStore(),
        TestBindings.standardResolver(),
        "test-binding-key",
        ledger);
  }

  /**
   * Create durable engine with custom ChatModel (for model-specific behavior tests).
   *
   * <p>Uses standard defaults except ChatModel.
   *
   * @param model custom chat model
   * @param store checkpoint store
   * @param resolver runtime binding resolver
   * @param tools tool callbacks
   * @return durable engine with custom model
   */
  public static SpringAiToolCallingEngine durableWithModel(
      ChatModel model,
      CheckpointStore store,
      RuntimeBindingResolver resolver,
      ToolCallback... tools) {
    return new SpringAiToolCallingEngine(
        model,
        List.of(tools),
        inMemoryChatMemory(),
        allowAllPolicy(),
        store,
        resolver,
        "test-binding-key");
  }

  /**
   * Create durable engine with custom governance policy.
   *
   * <p>Uses standard defaults except governance policy.
   *
   * @param policy custom governance policy
   * @param store checkpoint store
   * @param resolver runtime binding resolver
   * @param tools tool callbacks
   * @return durable engine with custom policy
   */
  public static SpringAiToolCallingEngine durableWithPolicy(
      ToolGovernancePolicy policy,
      CheckpointStore store,
      RuntimeBindingResolver resolver,
      ToolCallback... tools) {
    return new SpringAiToolCallingEngine(
        simpleChatModel(), List.of(tools), inMemoryChatMemory(), policy, store, resolver, "test-binding-key");
  }

  // ==================== Private Helpers ====================

  /**
   * Simple ChatModel that returns fixed response.
   *
   * <p>Sufficient for tests not focused on model behavior.
   */
  private static ChatModel simpleChatModel() {
    return new ChatModel() {
      @Override
      public ChatResponse call(Prompt prompt) {
        // Return simple fixed response
        return new ChatResponse(
            List.of(
                new Generation(
                    org.springframework.ai.chat.messages.AssistantMessage.builder()
                        .content("Test response")
                        .build())));
      }
    };
  }

  /**
   * Standard in-memory ChatMemory with window size 100.
   *
   * <p>Sufficient for most tests.
   */
  private static ChatMemory inMemoryChatMemory() {
    return MessageWindowChatMemory.builder().maxMessages(100).build();
  }

  /**
   * Allow-all governance policy.
   *
   * <p>Returns ALLOW for all tool calls. Use custom policy when testing governance behavior.
   */
  private static ToolGovernancePolicy allowAllPolicy() {
    return (toolName, functionCall, conversationHistory) -> GovernanceDecision.ALLOW;
  }
}
