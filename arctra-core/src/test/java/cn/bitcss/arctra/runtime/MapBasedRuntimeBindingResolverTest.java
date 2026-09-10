package cn.bitcss.arctra.runtime;

import static org.assertj.core.api.Assertions.*;

import cn.bitcss.arctra.agent.AgentDefinition;
import cn.bitcss.arctra.agent.AgentExecutionContext;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link MapBasedRuntimeBindingResolver}.
 *
 * <p>Verifies reference resolver behavior and M5 cross-runtime recovery contract.
 *
 * @author lov3r
 * @since M5-A2
 */
@DisplayName("MapBasedRuntimeBindingResolver")
class MapBasedRuntimeBindingResolverTest {

  private static final String DEPLOY_AGENT_KEY = "deploy-agent/v3";
  private static final String INCIDENT_AGENT_KEY = "incident-agent/v1";

  @Test
  @DisplayName("Exact logical key resolves successfully")
  void exactKeyResolvesSuccessfully() {
    AgentDefinition deployAgent = new AgentDefinition("Deploy Agent", "Manages deployments");
    Map<String, AgentDefinition> definitions = Map.of(DEPLOY_AGENT_KEY, deployAgent);

    RuntimeBindingResolver resolver = new MapBasedRuntimeBindingResolver(definitions);

    RuntimeBinding binding = resolver.resolve("process-1", DEPLOY_AGENT_KEY, "session-42");

    assertThat(binding.definition()).isEqualTo(deployAgent);
    assertThat(binding.context().sessionId()).isEqualTo("session-42");
  }

  @Test
  @DisplayName("Missing key fails with RuntimeBindingConfigurationException")
  void missingKeyFailsExplicitly() {
    Map<String, AgentDefinition> definitions = Map.of();
    RuntimeBindingResolver resolver = new MapBasedRuntimeBindingResolver(definitions);

    RuntimeBindingConfigurationException exception =
        catchThrowableOfType(
            () -> resolver.resolve("process-1", "non-existent-agent", "session-1"),
            RuntimeBindingConfigurationException.class);

    assertThat(exception.runtimeBindingKey()).isEqualTo("non-existent-agent");
    assertThat(exception.getMessage())
        .contains("AgentDefinition not found")
        .contains("non-existent-agent")
        .contains("process-1");
  }

  @Test
  @DisplayName("SessionId correctly reaches AgentExecutionContext")
  void sessionIdReachesExecutionContext() {
    AgentDefinition agent = new AgentDefinition("Test Agent", "Test");
    Map<String, AgentDefinition> definitions = Map.of("agent/v1", agent);
    RuntimeBindingResolver resolver = new MapBasedRuntimeBindingResolver(definitions);

    // With session
    RuntimeBinding withSession = resolver.resolve("process-1", "agent/v1", "session-123");
    assertThat(withSession.context().sessionId()).isEqualTo("session-123");

    // Stateless (null sessionId)
    RuntimeBinding stateless = resolver.resolve("process-1", "agent/v1", null);
    assertThat(stateless.context().sessionId()).isNull();

    // Stateless (blank sessionId)
    RuntimeBinding statelessBlank = resolver.resolve("process-1", "agent/v1", "  ");
    assertThat(statelessBlank.context().sessionId()).isNull();
  }

  @Test
  @DisplayName("Same logical key resolves from different resolver instances")
  void sameLogicalKeyResolvesCrossInstance() {
    AgentDefinition agent = new AgentDefinition("Deploy Agent", "Deployment automation");
    Map<String, AgentDefinition> definitions = Map.of(DEPLOY_AGENT_KEY, agent);

    // Runtime A resolver
    RuntimeBindingResolver resolverA = new MapBasedRuntimeBindingResolver(definitions);
    RuntimeBinding bindingA = resolverA.resolve("process-1", DEPLOY_AGENT_KEY, "session-1");

    // Runtime B resolver (different instance, same logical definitions)
    RuntimeBindingResolver resolverB = new MapBasedRuntimeBindingResolver(definitions);
    RuntimeBinding bindingB = resolverB.resolve("process-1", DEPLOY_AGENT_KEY, "session-1");

    // Physical objects differ
    assertThat(resolverA).isNotSameAs(resolverB);
    assertThat(bindingA).isNotSameAs(bindingB);

    // Logical equivalence
    assertThat(bindingA.definition()).isEqualTo(bindingB.definition());
    assertThat(bindingA.context()).isEqualTo(bindingB.context());
  }

  @Test
  @DisplayName("Null processId rejected")
  void nullProcessIdRejected() {
    RuntimeBindingResolver resolver = new MapBasedRuntimeBindingResolver(Map.of());

    assertThatThrownBy(() -> resolver.resolve(null, "key", "session"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("processId");
  }

  @Test
  @DisplayName("Blank processId rejected")
  void blankProcessIdRejected() {
    RuntimeBindingResolver resolver = new MapBasedRuntimeBindingResolver(Map.of());

    assertThatThrownBy(() -> resolver.resolve("  ", "key", "session"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("processId");
  }

  @Test
  @DisplayName("Null runtimeBindingKey rejected")
  void nullRuntimeBindingKeyRejected() {
    RuntimeBindingResolver resolver = new MapBasedRuntimeBindingResolver(Map.of());

    assertThatThrownBy(() -> resolver.resolve("process-1", null, "session"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("runtimeBindingKey");
  }

  @Test
  @DisplayName("Blank runtimeBindingKey rejected")
  void blankRuntimeBindingKeyRejected() {
    RuntimeBindingResolver resolver = new MapBasedRuntimeBindingResolver(Map.of());

    assertThatThrownBy(() -> resolver.resolve("process-1", "  ", "session"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("runtimeBindingKey");
  }

  @Test
  @DisplayName("RuntimeBinding contains only definition and context")
  void runtimeBindingStructure() {
    AgentDefinition agent = new AgentDefinition("Agent", "Description");
    RuntimeBindingResolver resolver =
        new MapBasedRuntimeBindingResolver(Map.of("agent/v1", agent));

    RuntimeBinding binding = resolver.resolve("process-1", "agent/v1", "session-1");

    // Verify structure: only definition and context
    assertThat(binding.definition()).isNotNull();
    assertThat(binding.context()).isNotNull();

    // RuntimeBinding does NOT contain engine/tools/model/policy
    // (verified by record definition - no such fields exist)
  }

  @Test
  @DisplayName("Multiple definitions coexist")
  void multipleDefinitionsCoexist() {
    AgentDefinition deployAgent = new AgentDefinition("Deploy Agent", "Deployments");
    AgentDefinition incidentAgent = new AgentDefinition("Incident Agent", "Incidents");

    Map<String, AgentDefinition> definitions = new HashMap<>();
    definitions.put(DEPLOY_AGENT_KEY, deployAgent);
    definitions.put(INCIDENT_AGENT_KEY, incidentAgent);

    RuntimeBindingResolver resolver = new MapBasedRuntimeBindingResolver(definitions);

    RuntimeBinding binding1 = resolver.resolve("p1", DEPLOY_AGENT_KEY, "s1");
    RuntimeBinding binding2 = resolver.resolve("p2", INCIDENT_AGENT_KEY, "s2");

    assertThat(binding1.definition()).isEqualTo(deployAgent);
    assertThat(binding2.definition()).isEqualTo(incidentAgent);
  }

  @Test
  @DisplayName("Constructor null definitions rejected")
  void constructorNullDefinitionsRejected() {
    assertThatThrownBy(() -> new MapBasedRuntimeBindingResolver(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("definitions");
  }

  @Test
  @DisplayName("Strict resolution: no fuzzy matching")
  void strictResolutionNoFuzzyMatching() {
    AgentDefinition agent = new AgentDefinition("Agent", "Description");
    Map<String, AgentDefinition> definitions = Map.of("deploy-agent/v3", agent);

    RuntimeBindingResolver resolver = new MapBasedRuntimeBindingResolver(definitions);

    // Exact match succeeds
    assertThatCode(() -> resolver.resolve("p1", "deploy-agent/v3", null)).doesNotThrowAnyException();

    // Similar but not exact keys fail with RuntimeBindingConfigurationException
    assertThatThrownBy(() -> resolver.resolve("p1", "deploy-agent/v4", null))
        .isInstanceOf(RuntimeBindingConfigurationException.class);

    assertThatThrownBy(() -> resolver.resolve("p1", "deploy-agent", null))
        .isInstanceOf(RuntimeBindingConfigurationException.class);

    assertThatThrownBy(() -> resolver.resolve("p1", "DEPLOY-AGENT/V3", null))
        .isInstanceOf(RuntimeBindingConfigurationException.class);
  }
}
