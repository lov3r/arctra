package cn.bitcss.arctra.runtime.react;

import static org.assertj.core.api.Assertions.assertThat;

import cn.bitcss.arctra.agent.AgentDefinition;
import cn.bitcss.arctra.agent.AgentRequest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * Tests for {@link SpringAiToolCallingEngine}.
 *
 * @author lov3r
 */
class SpringAiToolCallingEngineTest {

  @Test
  void should_execute_without_tools() {
    var fakeChatModel = new FakeChatModel("Hello from model");
    ChatMemory chatMemory = MessageWindowChatMemory.builder().build();
    var engine = new SpringAiToolCallingEngine(fakeChatModel, List.of(), chatMemory);

    var definition = new AgentDefinition("TestAgent", "A test agent");
    var request = new AgentRequest("Hi");

    var result = engine.execute(definition, request);

    assertThat(result.content()).isEqualTo("Hello from model");
    assertThat(result.evidences()).isEmpty();
  }

  @Test
  void should_use_agent_definition_in_system_prompt() {
    var fakeChatModel = new CapturingFakeChatModel();
    ChatMemory chatMemory = MessageWindowChatMemory.builder().build();
    var engine = new SpringAiToolCallingEngine(fakeChatModel, List.of(), chatMemory);

    var definition = new AgentDefinition("TestAgent", "A helpful assistant");
    var request = new AgentRequest("Hello");

    engine.execute(definition, request);

    // Verify system prompt was constructed from AgentDefinition
    var capturedPrompt = fakeChatModel.getLastPrompt();
    assertThat(capturedPrompt).contains("TestAgent");
    assertThat(capturedPrompt).contains("A helpful assistant");
  }

  @Test
  void should_handle_null_description() {
    var fakeChatModel = new CapturingFakeChatModel();
    ChatMemory chatMemory = MessageWindowChatMemory.builder().build();
    var engine = new SpringAiToolCallingEngine(fakeChatModel, List.of(), chatMemory);

    var definition = new AgentDefinition("TestAgent", null);
    var request = new AgentRequest("Hello");

    engine.execute(definition, request);

    // Should not fail, should construct minimal system prompt
    var capturedPrompt = fakeChatModel.getLastPrompt();
    assertThat(capturedPrompt).contains("TestAgent");
  }

  @Test
  void should_isolate_evidences_between_executions() {
    var tool = new ObservableMockTool();
    var fakeChatModel = new FakeChatModel("response");
    ChatMemory chatMemory = MessageWindowChatMemory.builder().build();
    var engine = new SpringAiToolCallingEngine(fakeChatModel, List.of(tool), chatMemory);

    var def = new AgentDefinition("Agent", "Test");

    var result1 = engine.execute(def, new AgentRequest("request1"));
    var result2 = engine.execute(def, new AgentRequest("request2"));

    // Each execution has its own evidence collection
    // Note: Without actual tool calling in FakeChatModel, evidences will be empty
    // This test mainly verifies no shared state between executions
    assertThat(result1.evidences()).isEmpty();
    assertThat(result2.evidences()).isEmpty();
  }

  /** Simple fake ChatModel that returns a fixed response. */
  static class FakeChatModel implements ChatModel {

    private final String response;

    FakeChatModel(String response) {
      this.response = response;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
      var message = new AssistantMessage(response);
      var generation = new Generation(message);
      return new ChatResponse(List.of(generation));
    }
  }

  /** Fake ChatModel that captures the prompt for verification. */
  static class CapturingFakeChatModel implements ChatModel {

    private String lastPrompt;

    @Override
    public ChatResponse call(Prompt prompt) {
      // Capture system + user messages
      this.lastPrompt =
          prompt.getInstructions().stream()
              .map(msg -> msg.getText())
              .reduce("", (a, b) -> a + " " + b);

      var message = new AssistantMessage("response");
      var generation = new Generation(message);
      return new ChatResponse(List.of(generation));
    }

    String getLastPrompt() {
      return lastPrompt;
    }
  }

  /** Observable mock tool for testing. */
  static class ObservableMockTool implements ToolCallback {

    @Override
    public ToolDefinition getToolDefinition() {
      return ToolDefinition.builder()
          .name("observableTool")
          .description("Observable tool")
          .inputSchema("{\"type\":\"object\",\"properties\":{}}")
          .build();
    }

    @Override
    public String call(String functionArguments) {
      return "tool result";
    }
  }
}
