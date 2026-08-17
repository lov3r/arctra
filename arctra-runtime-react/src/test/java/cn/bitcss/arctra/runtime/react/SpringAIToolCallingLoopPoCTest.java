package cn.bitcss.arctra.runtime.react;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * M1-T3 PoC: Spring AI Tool Calling Loop and Evidence Capture.
 *
 * <p>Validates approach A: Fully reuse Spring AI Tool Calling Loop.
 *
 * <p>This PoC explores:
 * <ul>
 *   <li>Spring AI 2.0 actual Tool Calling API
 *   <li>ToolCallback wrapper for Evidence capture
 *   <li>Observation points for tool execution
 * </ul>
 *
 * @author lov3r
 */
class SpringAIToolCallingLoopPoCTest {

  @Test
  void verify_tool_callback_can_be_wrapped_for_evidence() {
    // PoC: Wrap ToolCallback to capture evidence
    var actualTool = new MockQueryLogsTool();
    var observableTool = new EvidenceCapturingToolWrapper(actualTool);

    // Simulate tool execution
    var result = observableTool.call("{\"timeRange\": \"16:18-16:22\"}");

    // Verify tool was executed
    assertThat(result).contains("SQLException");

    // Verify evidence was captured
    assertThat(observableTool.wasInvoked()).isTrue();
    assertThat(observableTool.getCapturedToolName()).isEqualTo("queryLogs");
    assertThat(observableTool.getCapturedArguments()).isNotNull();
    assertThat(observableTool.getCapturedResult()).contains("SQLException");

    // Conclusion: We can wrap ToolCallback to capture Evidence
    // This is the observation point for Evidence collection
  }

  @Test
  void verify_chat_client_basic_flow() {
    // Verify ChatClient can be created and used
    var fakeChatModel = new SimpleFakeChatModel();
    var chatClient = ChatClient.builder(fakeChatModel).build();

    var response = chatClient.prompt().user("Test message").call().content();

    assertThat(response).isEqualTo("Fake response");
  }

  @Disabled("ToolCallingAdvisor requires ToolCallingManager - complex setup, defer to full implementation")
  @Test
  void explore_tool_calling_advisor_integration() {
    // This test is disabled because ToolCallingAdvisor setup is complex
    // Requires: ToolCallingManager, ToolCallbackProvider, etc.
    //
    // Decision: For M1-T6, we will:
    // 1. Use Spring AI's ToolCallingAdvisor as documented (approach A)
    // 2. Wrap ToolCallback for Evidence capture (as proven above)
    // 3. Let Spring AI handle the Tool Calling Loop
  }

  /**
   * Simple fake ChatModel for basic testing.
   */
  static class SimpleFakeChatModel implements ChatModel {

    @Override
    public ChatResponse call(Prompt prompt) {
      var message = new AssistantMessage("Fake response");
      var generation = new Generation(message);
      return new ChatResponse(List.of(generation));
    }
  }

  /**
   * Mock Tool for testing.
   */
  static class MockQueryLogsTool implements ToolCallback {

    @Override
    public ToolDefinition getToolDefinition() {
      return ToolDefinition.builder()
          .name("queryLogs")
          .description("Query application logs")
          .inputSchema(
              """
              {
                "type": "object",
                "properties": {
                  "timeRange": {"type": "string"}
                }
              }
              """)
          .build();
    }

    @Override
    public String call(String functionArguments) {
      return """
          {
            "logs": [
              "16:20:15 ERROR SQLException: Unknown column 'user_status'"
            ]
          }
          """;
    }
  }

  /**
   * Evidence-capturing ToolCallback wrapper.
   *
   * <p>This demonstrates the observation point for Evidence collection.
   * <p>Approach: Wrap actual ToolCallback, capture execution data before/after call.
   */
  static class EvidenceCapturingToolWrapper implements ToolCallback {

    private final ToolCallback delegate;
    private boolean invoked = false;
    private String capturedToolName;
    private String capturedArguments;
    private String capturedResult;

    EvidenceCapturingToolWrapper(ToolCallback delegate) {
      this.delegate = delegate;
    }

    @Override
    public ToolDefinition getToolDefinition() {
      return delegate.getToolDefinition();
    }

    @Override
    public String call(String functionArguments) {
      // Before execution: capture tool name and arguments
      this.invoked = true;
      this.capturedToolName = delegate.getToolDefinition().name();
      this.capturedArguments = functionArguments;

      // Execute actual tool
      var result = delegate.call(functionArguments);

      // After execution: capture result
      this.capturedResult = result;

      // Evidence can be created here:
      // new Evidence(capturedToolName, capturedResult)

      return result;
    }

    public boolean wasInvoked() {
      return invoked;
    }

    public String getCapturedToolName() {
      return capturedToolName;
    }

    public String getCapturedArguments() {
      return capturedArguments;
    }

    public String getCapturedResult() {
      return capturedResult;
    }
  }
}
