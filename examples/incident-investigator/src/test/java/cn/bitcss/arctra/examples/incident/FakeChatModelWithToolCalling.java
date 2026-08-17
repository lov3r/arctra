package cn.bitcss.arctra.examples.incident;

import java.util.List;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * Fake ChatModel for testing that simulates a simple response without actual tool calling.
 *
 * <p>Note: Simulating Spring AI's complete tool calling loop is complex. This fake model returns a
 * final response directly. For real tool calling behavior, see IncidentAgentRealE2ETest with actual
 * OpenAI API.
 *
 * @author lov3r
 */
class FakeChatModelWithToolCalling implements ChatModel {

  @Override
  public ChatResponse call(Prompt prompt) {
    // Return a simple analysis (Spring AI tool calling loop is too complex to fake)
    var analysis =
        """
        Based on the investigation:

        The production environment started experiencing 500 errors at 16:20.
        This appears to be related to a recent deployment and database schema issues.

        Analysis complete.
        """;

    var message = new AssistantMessage(analysis);
    var generation = new Generation(message);
    return new ChatResponse(List.of(generation));
  }
}
