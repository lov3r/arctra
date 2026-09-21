package cn.bitcss.arctra.procedure;

import cn.bitcss.arctra.execution.EventType;
import cn.bitcss.arctra.execution.ExecutionEvent;
import cn.bitcss.arctra.execution.ExecutionEventListener;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 执行跟踪收集器（用于候选提取）。
 *
 * <p><strong>M8-B：</strong>在执行期间收集工具调用序列，用于后续候选提取。
 *
 * <p>作为 ExecutionEventListener 实现，监听 TOOL_EXECUTED 和 COMPLETED 事件。
 *
 * <p>不通过 ExecutionLedger，而是直接从执行事件中收集。
 *
 * @author lov3r
 * @since M8-B
 */
public class ExecutionTraceCollector implements ExecutionEventListener {

  // processId -> 收集中的跟踪
  private final ConcurrentMap<String, TraceBuilder> activeTraces = new ConcurrentHashMap<>();

  private final CandidateExtractor extractor;
  private final ProcedureCandidateStore candidateStore;

  public ExecutionTraceCollector(
      CandidateExtractor extractor, ProcedureCandidateStore candidateStore) {
    this.extractor = Objects.requireNonNull(extractor, "extractor cannot be null");
    this.candidateStore = Objects.requireNonNull(candidateStore, "candidateStore cannot be null");
  }

  @Override
  public void onEvent(ExecutionEvent event) {
    switch (event.eventType()) {
      case PROCESS_STARTED -> handleProcessStarted(event);
      case TOOL_EXECUTED -> handleToolExecuted(event);
      case COMPLETED -> handleCompleted(event);
      case FAILED -> handleFailed(event);
      default -> {
        // 忽略其他事件类型
      }
    }
  }

  private void handleProcessStarted(ExecutionEvent event) {
    // 初始化跟踪构建器
    activeTraces.computeIfAbsent(event.processId(), k -> new TraceBuilder());
  }

  private void handleToolExecuted(ExecutionEvent event) {
    TraceBuilder builder = activeTraces.get(event.processId());
    if (builder != null) {
      // 解析工具事件负载
      // V1 简化：从负载中提取工具信息
      String toolName = extractToolName(event.payload());
      if (toolName != null) {
        // 记录工具调用（实际参数和结果需要从其他地方获取）
        builder.addToolInvocation(toolName, "hash-placeholder", "{}", "{}");
      }
    }
  }

  private void handleCompleted(ExecutionEvent event) {
    TraceBuilder builder = activeTraces.remove(event.processId());
    if (builder != null && builder.hasInvocations()) {
      // 执行成功完成，尝试提取候选
      tryExtractCandidate(event.processId(), builder);
    }
  }

  private void handleFailed(ExecutionEvent event) {
    // 失败的执行不提取候选
    activeTraces.remove(event.processId());
  }

  private void tryExtractCandidate(String processId, TraceBuilder builder) {
    try {
      ExecutionTrace trace = builder.build("unknown-agent", "unknown-intent");
      extractor.extract(trace, processId).ifPresent(candidateStore::save);
    } catch (Exception e) {
      // 提取失败不影响执行完成
      // 日志省略（实际应记录）
    }
  }

  private String extractToolName(String payload) {
    if (payload == null || payload.isBlank()) {
      return null;
    }
    // V1 简化：从 JSON 负载中提取 toolName
    // 实际应使用 JSON 解析器
    int start = payload.indexOf("\"toolName\":\"");
    if (start == -1) {
      return null;
    }
    start += 12; // "toolName":" 的长度
    int end = payload.indexOf("\"", start);
    if (end == -1) {
      return null;
    }
    return payload.substring(start, end);
  }

  /** 跟踪构建器（内部类）。 */
  private static class TraceBuilder {
    private final List<ExecutionTrace.ToolInvocation> invocations = new ArrayList<>();

    void addToolInvocation(
        String toolName, String inputSchemaHash, String arguments, String result) {
      invocations.add(new ExecutionTrace.ToolInvocation(toolName, inputSchemaHash, arguments, result));
    }

    boolean hasInvocations() {
      return !invocations.isEmpty();
    }

    ExecutionTrace build(String agentName, String intentKey) {
      return new ExecutionTrace(agentName, intentKey, List.copyOf(invocations));
    }
  }
}
