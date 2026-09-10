package cn.bitcss.arctra.runtime;

import cn.bitcss.arctra.agent.AgentDefinition;
import cn.bitcss.arctra.agent.AgentExecutionContext;
import java.util.Map;
import java.util.Objects;

/**
 * Map-based reference implementation of {@link RuntimeBindingResolver}.
 *
 * <p><strong>This is a reference implementation for testing and learning, not a production
 * framework component.</strong> Applications should implement {@link RuntimeBindingResolver}
 * according to their own definition storage strategy (database, config service, Spring beans,
 * etc.).
 *
 * <h2>Resolution Strategy</h2>
 *
 * <p>Performs strict exact-match lookup:
 *
 * <ul>
 *   <li>Exact {@code runtimeBindingKey} match → success
 *   <li>Missing key → {@link RuntimeBindingException}
 *   <li>No fuzzy matching, no automatic migration
 * </ul>
 *
 * <h2>Cross-Runtime Recovery Contract</h2>
 *
 * <p>Different physical runtime instances (different JVMs, machines, or resolver instances) can
 * resolve the same logical binding key to logically equivalent {@link RuntimeBinding}:
 *
 * <pre>
 * Runtime A (JVM 1):
 *   resolver_A.resolve("process-1", "deploy-agent/v3", "session-42")
 *   → RuntimeBinding(AgentDefinition("Deploy Agent", ...), AgentExecutionContext("session-42"))
 *
 * Runtime B (JVM 2):
 *   resolver_B.resolve("process-1", "deploy-agent/v3", "session-42")
 *   → RuntimeBinding(AgentDefinition("Deploy Agent", ...), AgentExecutionContext("session-42"))
 * </pre>
 *
 * <p>Physical Java objects differ; logical semantics must be equivalent.
 *
 * <h2>Session Context Reconstruction</h2>
 *
 * <p>The {@code sessionId} from checkpoint is used to reconstruct {@link AgentExecutionContext}.
 * For cross-runtime recovery, the underlying {@code ChatMemory} must be accessible across runtime
 * instances via this {@code sessionId}.
 *
 * <p><strong>Important:</strong> {@code InMemoryChatMemory} only supports single-JVM scenarios.
 * Production cross-runtime recovery requires persistent {@code ChatMemory} (e.g., Redis-backed).
 *
 * <h2>Execution Authority</h2>
 *
 * <p>This resolver only reconstructs {@link RuntimeBinding} (definition + context). It does NOT
 * provide:
 *
 * <ul>
 *   <li>Execution engine
 *   <li>Tools
 *   <li>Model
 *   <li>Governance policy
 *   <li>ChatMemory instance
 * </ul>
 *
 * <p>Execution authority belongs to the calling {@link DurableExecutionEngine}, which uses its own
 * resources.
 *
 * @author lov3r
 * @since M5-A2
 */
public class MapBasedRuntimeBindingResolver implements RuntimeBindingResolver {

  private final Map<String, AgentDefinition> definitions;

  /**
   * Create resolver with definition map.
   *
   * @param definitions map from runtimeBindingKey to AgentDefinition (immutable copy recommended)
   * @throws NullPointerException if definitions is null
   */
  public MapBasedRuntimeBindingResolver(Map<String, AgentDefinition> definitions) {
    this.definitions = Objects.requireNonNull(definitions, "definitions cannot be null");
  }

  /**
   * Resolve runtime binding from checkpoint identity.
   *
   * <p>Performs strict exact-match lookup. Missing keys fail explicitly without fallback.
   *
   * @param processId process identifier (for logging/diagnostics)
   * @param runtimeBindingKey logical binding key from checkpoint
   * @param sessionId session identifier from checkpoint (may be null for stateless)
   * @return resolved runtime binding
   * @throws RuntimeBindingException if binding key not found or resolution fails
   * @throws IllegalArgumentException if processId or runtimeBindingKey is null/blank
   */
  @Override
  public RuntimeBinding resolve(String processId, String runtimeBindingKey, String sessionId) {
    // Validate inputs (contract enforcement)
    if (processId == null || processId.isBlank()) {
      throw new IllegalArgumentException("processId cannot be null or blank");
    }
    if (runtimeBindingKey == null || runtimeBindingKey.isBlank()) {
      throw new IllegalArgumentException("runtimeBindingKey cannot be null or blank");
    }

    // Strict exact-match lookup
    AgentDefinition definition = definitions.get(runtimeBindingKey);
    if (definition == null) {
      throw new RuntimeBindingConfigurationException(
          runtimeBindingKey,
          "AgentDefinition not found for key '"
              + runtimeBindingKey
              + "' (processId: "
              + processId
              + ")");
    }

    // Reconstruct execution context with checkpoint's sessionId
    AgentExecutionContext context =
        (sessionId != null && !sessionId.isBlank())
            ? AgentExecutionContext.withSession(sessionId)
            : AgentExecutionContext.stateless();

    return new RuntimeBinding(definition, context);
  }
}
