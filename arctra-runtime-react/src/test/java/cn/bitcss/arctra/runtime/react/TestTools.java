package cn.bitcss.arctra.runtime.react;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/**
 * Test utility tools for invocation intent testing.
 *
 * @author lov3r
 * @since M6-T4A
 */
class TestTools {

  /**
   * Create a counting tool that increments counter on each call.
   *
   * @param toolName tool name
   * @param callCount atomic counter to track invocations
   * @return counting tool callback
   */
  static ToolCallback createCountingTool(String toolName, AtomicInteger callCount) {
    return new ToolCallback() {
      @Override
      public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
            .name(toolName)
            .description("Test counting tool")
            .inputSchema("{\"type\":\"object\",\"properties\":{}}")
            .build();
      }

      @Override
      public String call(String functionArguments) {
        callCount.incrementAndGet();
        return "counted";
      }
    };
  }

  /**
   * Create a recording tool that records execution events to a list.
   *
   * @param toolName tool name
   * @param executionOrder list to record events
   * @param eventPrefix event prefix
   * @return recording tool callback
   */
  static ToolCallback createRecordingTool(
      String toolName, List<String> executionOrder, String eventPrefix) {
    return new ToolCallback() {
      @Override
      public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
            .name(toolName)
            .description("Test recording tool")
            .inputSchema("{\"type\":\"object\",\"properties\":{}}")
            .build();
      }

      @Override
      public String call(String functionArguments) {
        executionOrder.add(eventPrefix + ":" + toolName);
        return "recorded";
      }
    };
  }

  /**
   * Create a failing tool that throws exception on call.
   *
   * @param toolName tool name
   * @param exception exception to throw
   * @return failing tool callback
   */
  static ToolCallback createFailingTool(String toolName, RuntimeException exception) {
    return new ToolCallback() {
      @Override
      public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
            .name(toolName)
            .description("Test failing tool")
            .inputSchema("{\"type\":\"object\",\"properties\":{}}")
            .build();
      }

      @Override
      public String call(String functionArguments) {
        throw exception;
      }
    };
  }

  /**
   * Create a successful tool that returns a fixed result.
   *
   * @param toolName tool name
   * @param result result to return
   * @return successful tool callback
   */
  static ToolCallback createSuccessfulTool(String toolName, String result) {
    return new ToolCallback() {
      @Override
      public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
            .name(toolName)
            .description("Test successful tool")
            .inputSchema("{\"type\":\"object\",\"properties\":{}}")
            .build();
      }

      @Override
      public String call(String functionArguments) {
        return result;
      }
    };
  }
}
