package cn.bitcss.arctra.agent;

/**
 * Defines an agent's identity and purpose.
 *
 * <p>An agent definition is a static configuration that describes what an agent is, not how it
 * runs. Engine selection and execution details are determined at runtime.
 *
 * <p>This is an immutable domain model. Identity semantics will be determined when agent
 * registration, versioning, or persistence requirements emerge.
 *
 * @author lov3r
 */
public record AgentDefinition(String name, String description) {

  public AgentDefinition {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("name cannot be blank");
    }
  }
}
