package cn.bitcss.arctra.runtime.react;

import cn.bitcss.arctra.agent.AgentDefinition;
import cn.bitcss.arctra.agent.AgentExecutionContext;
import cn.bitcss.arctra.runtime.RuntimeBinding;
import cn.bitcss.arctra.runtime.RuntimeBindingException;
import cn.bitcss.arctra.runtime.RuntimeBindingResolver;

/**
 * Test fixture factory for RuntimeBinding and RuntimeBindingResolver.
 *
 * <p>Provides standard implementations to reduce repetitive resolver construction across test
 * classes.
 *
 * <p><strong>Design Principles:</strong>
 *
 * <ul>
 *   <li>Provides common resolver patterns (standard, failing)
 *   <li>Tests verifying specific binding behavior should use explicit implementations
 *   <li>Does NOT hide resolver logic when it's semantically important to the test
 * </ul>
 *
 * @author lov3r
 * @since M6-T2.5A-R1
 */
public final class TestBindings {

  private TestBindings() {
    throw new UnsupportedOperationException("Utility class");
  }

  /**
   * Standard test agent definition used by standard resolver.
   *
   * <p>Tests verifying definition-specific behavior should use explicit definitions.
   */
  public static final AgentDefinition STANDARD_DEFINITION =
      new AgentDefinition("test-agent", "Test agent for standard scenarios");

  /**
   * Create standard RuntimeBindingResolver.
   *
   * <p>Returns a RuntimeBinding with:
   *
   * <ul>
   *   <li>Standard test agent definition
   *   <li>AgentExecutionContext with the provided sessionId
   * </ul>
   *
   * <p>This is the most common pattern across tests. Sufficient when test focus is not on binding
   * resolution itself.
   *
   * @return standard binding resolver
   */
  public static RuntimeBindingResolver standardResolver() {
    return (processId, bindingKey, sessionId) ->
        new RuntimeBinding(STANDARD_DEFINITION, AgentExecutionContext.withSession(sessionId));
  }

  /**
   * Create RuntimeBindingResolver that always fails with specified exception.
   *
   * <p>Use when testing binding failure scenarios (e.g., CHECK A should not proceed to tool
   * execution when binding fails).
   *
   * @param exception exception to throw (explicit - test-critical for failure tests)
   * @return failing binding resolver
   */
  public static RuntimeBindingResolver failingResolver(RuntimeBindingException exception) {
    return (processId, bindingKey, sessionId) -> {
      throw exception;
    };
  }

  /**
   * Create RuntimeBindingResolver with custom definition.
   *
   * <p>Use when testing definition-specific behavior (e.g., different agent instructions, metadata
   * handling).
   *
   * @param definition custom agent definition (explicit - test-critical)
   * @return resolver returning custom definition
   */
  public static RuntimeBindingResolver resolverWithDefinition(AgentDefinition definition) {
    return (processId, bindingKey, sessionId) ->
        new RuntimeBinding(definition, AgentExecutionContext.withSession(sessionId));
  }

  /**
   * Create standard RuntimeBinding directly.
   *
   * <p>Use when test needs a RuntimeBinding instance but not through resolver (e.g., direct API
   * testing).
   *
   * @param sessionId session ID
   * @return standard runtime binding
   */
  public static RuntimeBinding standardBinding(String sessionId) {
    return new RuntimeBinding(STANDARD_DEFINITION, AgentExecutionContext.withSession(sessionId));
  }
}
