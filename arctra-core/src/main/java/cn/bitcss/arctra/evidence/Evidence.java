package cn.bitcss.arctra.evidence;

/**
 * Evidence collected during agent execution.
 *
 * <p>Evidence represents observable, recordable, and referenceable facts about agent execution,
 * such as tool results, retrieval results, external system results, human input, or structured
 * model outputs. It does NOT include private model reasoning or chain-of-thought.
 *
 * <p>Evidence is a framework-agnostic concept applicable to all agent scenarios.
 *
 * @param source identifier of the evidence source (e.g., tool name, retriever name)
 * @param content the evidence content (tool result, retrieved document, external data, etc.)
 * @author lov3r
 */
public record Evidence(String source, String content) {

  public Evidence {
    if (source == null || source.isBlank()) {
      throw new IllegalArgumentException("source cannot be null or blank");
    }
    if (content == null) {
      throw new IllegalArgumentException("content cannot be null");
    }
  }
}
