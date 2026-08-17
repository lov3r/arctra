package cn.bitcss.arctra.runtime.react;

import cn.bitcss.arctra.evidence.Evidence;
import java.util.List;
import java.util.Objects;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/**
 * ToolCallback wrapper that captures evidence during tool execution.
 *
 * <p>This is an internal helper for per-execution evidence collection. Each execution creates
 * fresh wrappers with a local evidence list.
 *
 * <p>Transparently proxies all ToolCallback methods to ensure evidence is captured regardless of
 * which call path Spring AI uses.
 *
 * <p>Public for testing purposes, but primarily intended for internal use by SpringAiToolCallingEngine.
 *
 * @author lov3r
 */
public class EvidenceCapturingToolCallback implements ToolCallback {

  private final ToolCallback delegate;
  private final List<Evidence> evidences;

  public EvidenceCapturingToolCallback(ToolCallback delegate, List<Evidence> evidences) {
    this.delegate = Objects.requireNonNull(delegate, "delegate cannot be null");
    this.evidences = Objects.requireNonNull(evidences, "evidences cannot be null");
  }

  @Override
  public ToolDefinition getToolDefinition() {
    // Delegate unchanged
    return delegate.getToolDefinition();
  }

  @Override
  public ToolMetadata getToolMetadata() {
    // Delegate unchanged (Spring AI 2.0 default implementation)
    return delegate.getToolMetadata();
  }

  @Override
  public String call(String functionArguments) {
    // Execute delegate
    var result = delegate.call(functionArguments);

    // Capture evidence (only on success)
    captureEvidence(result);

    return result;
  }

  @Override
  public String call(String functionArguments, ToolContext toolContext) {
    // Execute delegate with context
    var result = delegate.call(functionArguments, toolContext);

    // Capture evidence (only on success)
    captureEvidence(result);

    return result;
  }

  private void captureEvidence(String result) {
    var toolName = delegate.getToolDefinition().name();
    var source = "tool:" + toolName;
    evidences.add(new Evidence(source, result));
  }
}
